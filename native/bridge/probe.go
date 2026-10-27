package bridge

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	mux "github.com/sagernet/sing-mux"
	qtls "github.com/sagernet/sing-quic"
	snell "github.com/sagernet/sing-snell"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const (
	StageTransport = "transport"
	StageTLS       = "tls"
	StageGet204    = "get204"
)

const (
	defaultProbeTimeoutMs   = 5000
	defaultProbeConcurrency = 8
	maxProbeBody            = 64 << 10
	maxThroughputBytes      = 1 << 20
)

// ProbeRequest: NodesJSON is a TUN-less sing-box config; DNSCSV, Interface* and IPv4Only describe the underlying network.
// TimeoutsJSON optionally maps a tag to its own timeout in milliseconds, overriding TimeoutMs.
type ProbeRequest struct {
	NodesJSON      string
	URL            string
	DNSCSV         string
	InterfaceName  string
	InterfaceIndex int32
	TimeoutMs      int32
	Concurrency    int32
	IPv4Only       bool
	TimeoutsJSON   string
}

// ProbeResult: OK means exactly HTTP 204 with an empty body; Stage names the step that failed or answered.
// ConnectMs is the time until the connection to the target was usable, FirstMs the first request including it,
// RttMs a second request on the same connection when Warm, else FirstMs.
type ProbeResult struct {
	OK        bool
	Stage     string
	Error     string
	Code      int32
	BodyLen   int32
	RttMs     int32
	ConnectMs int32
	FirstMs   int32
	Warm      bool
}

type probeNodeResult struct {
	Tag       string `json:"tag"`
	OK        bool   `json:"ok"`
	Stage     string `json:"stage"`
	Code      int    `json:"code"`
	BodyLen   int    `json:"body_len"`
	RttMs     int64  `json:"rtt_ms"`
	ConnectMs int64  `json:"connect_ms"`
	FirstMs   int64  `json:"first_ms"`
	Warm      bool   `json:"warm"`
	Error     string `json:"error,omitempty"`
}

// ThroughputResult: OK means an HTTP 200 answered; Bytes of its body arrived within BodyMs, capped by the request.
type ThroughputResult struct {
	OK     bool
	Code   int32
	Bytes  int32
	BodyMs int32
	Error  string
}

// probeStartedHook lets the listener guard test sample sockets while the probe box is up.
var probeStartedHook func()

var skippedProbeTypes = map[string]bool{
	C.TypeDirect:   true,
	C.TypeBlock:    true,
	C.TypeDNS:      true,
	C.TypeSelector: true,
	C.TypeURLTest:  true,
}

// Probe starts every node of req.NodesJSON in a private box and returns a JSON array of per-tag results; success means exactly HTTP 204.
func Probe(req *ProbeRequest, protector Protector) (string, error) {
	return onGoroutineErr(func() (string, error) {
		out, err := probePool(req, protector)
		return out, maskErr(err)
	})
}

func probePool(req *ProbeRequest, protector Protector) (string, error) {
	if req == nil || req.URL == "" {
		return "", E.New("probe: url is required")
	}
	if C.IsAndroid && protector == nil {
		return "", E.New("probe: protector is required on Android")
	}
	config, err := probeConfig(req.NodesJSON, req.DNSCSV, req.IPv4Only)
	if err != nil {
		return "", err
	}
	timeouts := map[string]int32{}
	if req.TimeoutsJSON != "" {
		if err := json.Unmarshal([]byte(req.TimeoutsJSON), &timeouts); err != nil {
			return "", E.Cause(err, "probe: parse timeouts")
		}
	}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	defer cancel()
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newProbePlatform(protector, req.InterfaceName, req.InterfaceIndex))
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, config)
	if err != nil {
		return "", E.Cause(err, "probe: decode config")
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return "", E.Cause(err, "probe: create box")
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		return "", E.Cause(err, "probe: start box")
	}
	if probeStartedHook != nil {
		probeStartedHook()
	}

	var dialers []adapter.Outbound
	for _, ob := range instance.Outbound().Outbounds() {
		if !skippedProbeTypes[ob.Type()] {
			dialers = append(dialers, ob)
		}
	}
	for _, ep := range instance.Endpoint().Endpoints() {
		dialers = append(dialers, ep)
	}
	dialers = withoutChainHops(dialers)

	fallback := positive(req.TimeoutMs, defaultProbeTimeoutMs)
	sem := make(chan struct{}, positive(req.Concurrency, defaultProbeConcurrency))
	results := make([]probeNodeResult, len(dialers))
	var wg sync.WaitGroup
	for i, d := range dialers {
		wg.Go(func() {
			sem <- struct{}{}
			defer func() { <-sem }()
			timeout := time.Duration(positive(timeouts[d.Tag()], fallback)) * time.Millisecond
			r := probeHTTP(ctx, d, req.URL, timeout)
			r.Tag = d.Tag()
			results[i] = r
		})
	}
	wg.Wait()
	out, err := json.Marshal(results)
	return string(out), err
}

// maxChainHops matches the hop limit of the config generator, which tags hop i of node T as "T-i".
const maxChainHops = 3

// withoutChainHops drops the helper outbounds a chained node dials through; they are probed only as part of their node.
func withoutChainHops(dialers []adapter.Outbound) []adapter.Outbound {
	tags := make(map[string]bool, len(dialers))
	for _, d := range dialers {
		tags[d.Tag()] = true
	}
	out := dialers[:0:0]
	for _, d := range dialers {
		if !isChainHop(d.Tag(), tags) {
			out = append(out, d)
		}
	}
	return out
}

func isChainHop(tag string, tags map[string]bool) bool {
	i := strings.LastIndexByte(tag, '-')
	if i <= 0 || i != len(tag)-2 {
		return false
	}
	n := tag[i+1] - '0'
	return n >= 1 && n <= maxChainHops && tags[tag[:i]]
}

// typeFreeTurn is the process-singleton outbound type; a probe box must never start one.
const typeFreeTurn = "freeturn"

// probeConfig rejects anything that listens, writes files or touches process-wide state, and pins DNS to the underlying network.
// ipv4Only drops AAAA lookups, which a network without IPv6 can never use.
func probeConfig(nodesJSON, dnsCSV string, ipv4Only bool) ([]byte, error) {
	var config map[string]any
	if err := json.Unmarshal([]byte(nodesJSON), &config); err != nil {
		return nil, E.Cause(err, "probe: parse config")
	}
	for _, key := range []string{"inbounds", "experimental", "services"} {
		if _, ok := config[key]; ok {
			return nil, E.New("probe: config must not contain ", key)
		}
	}
	if logOptions, _ := config["log"].(map[string]any); logOptions != nil {
		if _, ok := logOptions["output"]; ok {
			return nil, E.New("probe: config must not contain log.output")
		}
	}
	// An endpoint detouring to such an outbound fails too: its detour tag cannot resolve once the outbound is refused.
	for _, section := range []string{"outbounds", "endpoints"} {
		for _, item := range objects(config[section]) {
			if item["type"] == typeFreeTurn {
				return nil, E.New("probe: config must not contain a ", typeFreeTurn, " ", section[:len(section)-1])
			}
		}
	}
	route, _ := config["route"].(map[string]any)
	if route == nil {
		route = map[string]any{}
	}
	route["auto_detect_interface"] = true
	config["route"] = route
	// Without it the local resolver follows the default network, which is the tunnel once it is up.
	server, ok := firstDNSServer(dnsCSV)
	if !ok {
		return nil, E.New("probe: no DNS server of the underlying network")
	}
	dns := map[string]any{
		"servers": []any{map[string]any{"type": "udp", "tag": "local", "server": server, "server_port": 53}},
		"final":   "local",
	}
	route["default_domain_resolver"] = "local"
	if ipv4Only {
		dns["strategy"] = strategyIPv4Only
		route["default_domain_resolver"] = map[string]any{"server": "local", "strategy": strategyIPv4Only}
		for _, section := range []string{"outbounds", "endpoints"} {
			for _, item := range objects(config[section]) {
				if resolver, ok := item["domain_resolver"].(map[string]any); ok {
					resolver["strategy"] = strategyIPv4Only
				}
			}
		}
	}
	config["dns"] = dns
	return json.Marshal(config)
}

const strategyIPv4Only = "ipv4_only"

func objects(v any) []map[string]any {
	items, _ := v.([]any)
	var out []map[string]any
	for _, item := range items {
		if m, ok := item.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func firstDNSServer(csv string) (string, bool) {
	var fallback string
	for _, s := range strings.Split(csv, ",") {
		addr, err := netip.ParseAddr(strings.TrimSpace(s))
		if err != nil {
			continue
		}
		if addr.Is4() {
			return addr.String(), true
		}
		if fallback == "" {
			fallback = addr.String()
		}
	}
	return fallback, fallback != ""
}

// ProbeOutbound probes one outbound of the running service; the error is set only when nothing could run.
func ProbeOutbound(server *libbox.CommandServer, tag, url string, timeoutMs int32) (*ProbeResult, error) {
	return onGoroutineErr(func() (*ProbeResult, error) {
		r, err := probeLive(server, tag, url, timeoutMs)
		return r, maskErr(err)
	})
}

func probeLive(server *libbox.CommandServer, tag, url string, timeoutMs int32) (*ProbeResult, error) {
	b, err := runningBox(server)
	if err != nil {
		return nil, err
	}
	outbound, ok := b.Outbound().Outbound(tag)
	if !ok {
		return nil, E.New("probe: outbound not found: ", tag)
	}
	timeout := time.Duration(positive(timeoutMs, defaultProbeTimeoutMs)) * time.Millisecond
	return probeHTTP(context.Background(), outbound, url, timeout).result(), nil
}

func (r probeNodeResult) result() *ProbeResult {
	return &ProbeResult{
		OK: r.OK, Stage: r.Stage, Error: r.Error, Code: int32(r.Code), BodyLen: int32(r.BodyLen),
		RttMs: int32(r.RttMs), ConnectMs: int32(r.ConnectMs), FirstMs: int32(r.FirstMs), Warm: r.Warm,
	}
}

// ThroughputOutbound downloads up to maxBytes of url through one outbound of the running service; the error is set only when nothing could run.
func ThroughputOutbound(server *libbox.CommandServer, tag, url string, maxBytes, timeoutMs int32) (*ThroughputResult, error) {
	return onGoroutineErr(func() (*ThroughputResult, error) {
		b, err := runningBox(server)
		if err != nil {
			return nil, maskErr(err)
		}
		outbound, ok := b.Outbound().Outbound(tag)
		if !ok {
			return nil, E.New("throughput: outbound not found: ", tag)
		}
		limit := int64(positive(maxBytes, maxProbeBody))
		if limit > maxThroughputBytes {
			limit = maxThroughputBytes
		}
		timeout := time.Duration(positive(timeoutMs, defaultProbeTimeoutMs)) * time.Millisecond
		return throughputHTTP(context.Background(), outbound, url, limit, timeout), nil
	})
}

func throughputHTTP(parent context.Context, dialer N.Dialer, url string, limit int64, timeout time.Duration) *ThroughputResult {
	ctx, cancel := context.WithTimeout(warmContext(parent, dialer), timeout)
	defer cancel()
	transport := newProbeTransport(dialer, nil)
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return &ThroughputResult{Error: err.Error()}
	}
	resp, err := client.Do(req)
	if err != nil {
		return &ThroughputResult{Error: maskSecrets(err.Error())}
	}
	defer resp.Body.Close()
	start := time.Now()
	n, err := io.Copy(io.Discard, io.LimitReader(resp.Body, limit))
	r := &ThroughputResult{OK: resp.StatusCode == http.StatusOK, Code: int32(resp.StatusCode), Bytes: int32(n), BodyMs: int32(time.Since(start).Milliseconds())}
	if !r.OK {
		r.Error = "unexpected response " + resp.Status
	} else if err != nil {
		r.Error = maskSecrets(err.Error())
	}
	return r
}

// warmContext keeps a multiplexed or QUIC session open across connections, so a second request does not pay its handshake again.
func warmContext(ctx context.Context, dialer N.Dialer) context.Context {
	if m, ok := common.Cast[adapter.OutboundWithMultiplex](dialer); !ok || !m.MultiplexEnabled() {
		return ctx
	}
	ctx = adapter.ContextWithKeepSession(ctx)
	ctx = mux.ContextWithKeepSession(ctx)
	ctx = qtls.ContextWithKeepSession(ctx)
	return snell.ContextWithKeepSession(ctx)
}

func newProbeTransport(dialer N.Dialer, dialed *atomic.Bool) *http.Transport {
	return &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			conn, err := dialer.DialContext(ctx, network, M.ParseSocksaddr(addr))
			if err == nil && dialed != nil {
				dialed.Store(true)
			}
			return conn, err
		},
		TLSClientConfig:     &tls.Config{RootCAs: probeRootPool},
		ForceAttemptHTTP2:   true,
		MaxIdleConnsPerHost: 1,
	}
}

// probeRootPool replaces the system roots in tests only.
var probeRootPool *x509.CertPool

// probeHTTP measures in stages over one kept connection: connect, a first request, then a warm second one.
// A failed second request still passes, with the first request's time and Warm unset.
func probeHTTP(parent context.Context, dialer N.Dialer, url string, timeout time.Duration) probeNodeResult {
	ctx, cancel := context.WithTimeout(warmContext(parent, dialer), timeout)
	defer cancel()
	var dialed, tlsDone atomic.Bool
	transport := newProbeTransport(dialer, &dialed)
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport:     transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	start := time.Now()
	var connectAt atomic.Int64
	trace := &httptrace.ClientTrace{
		TLSHandshakeDone: func(_ tls.ConnectionState, err error) { tlsDone.Store(err == nil) },
		GotConn: func(info httptrace.GotConnInfo) {
			if !info.Reused {
				connectAt.CompareAndSwap(0, int64(time.Since(start)))
			}
		},
	}
	get := func() (int, int64, error) {
		req, err := http.NewRequestWithContext(httptrace.WithClientTrace(ctx, trace), http.MethodGet, url, nil)
		if err != nil {
			return 0, 0, err
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, 0, err
		}
		n, _ := io.Copy(io.Discard, io.LimitReader(resp.Body, maxProbeBody))
		_ = resp.Body.Close()
		return resp.StatusCode, n, nil
	}
	code, n, err := get()
	if err != nil {
		stage := StageGet204
		switch {
		case !dialed.Load():
			stage = StageTransport
		case strings.HasPrefix(url, "https:") && !tlsDone.Load():
			stage = StageTLS
		}
		return probeNodeResult{Stage: stage, Error: maskSecrets(err.Error())}
	}
	first := time.Since(start)
	r := probeNodeResult{Stage: StageGet204, Code: code, BodyLen: int(n), RttMs: first.Milliseconds(), FirstMs: first.Milliseconds()}
	r.ConnectMs = time.Duration(connectAt.Load()).Milliseconds()
	if connectAt.Load() == 0 {
		r.ConnectMs = r.FirstMs
	}
	r.OK = code == http.StatusNoContent && n == 0
	if !r.OK {
		r.Error = "unexpected response " + strconv.Itoa(code) + " " + http.StatusText(code)
		return r
	}
	second := time.Now()
	if code, n, err := get(); err == nil && code == http.StatusNoContent && n == 0 {
		r.RttMs = time.Since(second).Milliseconds()
		r.Warm = true
	}
	return r
}

func positive(v int32, fallback int) int {
	if v > 0 {
		return int(v)
	}
	return fallback
}
