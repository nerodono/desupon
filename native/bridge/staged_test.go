package bridge

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"

	box "github.com/sagernet/sing-box"
)

// delayRelay forwards TCP to target, delaying every chunk by oneWay in each direction without accumulating delay.
func delayRelay(t *testing.T, target string, oneWay time.Duration) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	pump := func(dst net.Conn, src net.Conn) {
		type chunk struct {
			at   time.Time
			data []byte
		}
		queue := make(chan chunk, 1024)
		go func() {
			defer dst.Close()
			for c := range queue {
				time.Sleep(time.Until(c.at.Add(oneWay)))
				if _, err := dst.Write(c.data); err != nil {
					return
				}
			}
		}()
		buf := make([]byte, 64<<10)
		for {
			n, err := src.Read(buf)
			if n > 0 {
				queue <- chunk{time.Now(), append([]byte(nil), buf[:n]...)}
			}
			if err != nil {
				close(queue)
				return
			}
		}
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				up, err := net.Dial("tcp", target)
				if err != nil {
					c.Close()
					return
				}
				go pump(up, c)
				pump(c, up)
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port
}

// ssServer starts a shadowsocks server box and returns its port.
func ssServer(t *testing.T) int {
	t.Helper()
	port := freePort(t)
	startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "inbounds": [{"type": "shadowsocks", "tag": "ss-in", "listen": "127.0.0.1", "listen_port": %d, "method": "aes-128-gcm", "password": "synthetic"}],
  "outbounds": [{"type": "direct", "tag": "direct"}]
}`, port))
	return port
}

func ssNode(tag string, port int) string {
	return fmt.Sprintf(`{"type": "shadowsocks", "tag": %q, "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": "synthetic"}`, tag, port)
}

func probeOne(t *testing.T, req *ProbeRequest) map[string]probeNodeResult {
	t.Helper()
	if req.DNSCSV == "" {
		req.DNSCSV = "192.0.2.53"
	}
	out, err := Probe(req, &countingProtector{})
	if err != nil {
		t.Fatal(err)
	}
	var results []probeNodeResult
	if err := json.Unmarshal([]byte(out), &results); err != nil {
		t.Fatal(err)
	}
	byTag := map[string]probeNodeResult{}
	for _, r := range results {
		byTag[r.Tag] = r
	}
	return byTag
}

// TestProbeStagedWarm: on a 40 ms link the warm request costs one round trip; connect pays the target TLS handshake, the first request both.
func TestProbeStagedWarm(t *testing.T) {
	fixture := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	t.Cleanup(fixture.Close)
	pool := x509.NewCertPool()
	pool.AddCert(fixture.Certificate())
	probeRootPool = pool
	t.Cleanup(func() { probeRootPool = nil })
	relay := delayRelay(t, fmt.Sprintf("127.0.0.1:%d", ssServer(t)), 20*time.Millisecond)
	config := fmt.Sprintf(`{"log": {"level": "error"}, "outbounds": [%s]}`, ssNode("n-slow", relay))

	r := probeOne(t, &ProbeRequest{NodesJSON: config, URL: fixture.URL + "/generate_204", TimeoutMs: 5000})["n-slow"]
	t.Logf("40 ms link: connect=%d first=%d warm rtt=%d", r.ConnectMs, r.FirstMs, r.RttMs)
	if !r.OK || !r.Warm {
		t.Fatalf("n-slow = %+v, want ok and warm", r)
	}
	if r.ConnectMs > r.FirstMs || r.RttMs >= r.FirstMs {
		t.Fatalf("stages out of order: %+v", r)
	}
	if r.RttMs < 35 || r.RttMs > 200 || r.ConnectMs < 35 || r.FirstMs < 75 {
		t.Fatalf("stages do not match a 40 ms link: %+v", r)
	}
}

// TestProbeSecondRequestFailureKeepsFirst: a node that answers once still passes, timed by its first request.
func TestProbeSecondRequestFailureKeepsFirst(t *testing.T) {
	var hits atomic.Int32
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits.Add(1) == 1 {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(fixture.Close)
	config := fmt.Sprintf(`{"log": {"level": "error"}, "outbounds": [%s]}`, ssNode("n-once", ssServer(t)))

	r := probeOne(t, &ProbeRequest{NodesJSON: config, URL: fixture.URL + "/generate_204", TimeoutMs: 3000})["n-once"]
	if !r.OK || r.Warm || r.RttMs != r.FirstMs || r.Code != http.StatusNoContent {
		t.Fatalf("n-once = %+v, want ok, cold, rtt = first", r)
	}
	if hits.Load() != 2 {
		t.Fatalf("fixture saw %d requests, want 2", hits.Load())
	}
}

// TestProbePerNodeTimeouts: a silent node with its own short timeout does not hold the probe for the default one.
func TestProbePerNodeTimeouts(t *testing.T) {
	fixture := newFixture(t)
	silent, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { silent.Close() })
	go func() {
		for {
			c, err := silent.Accept()
			if err != nil {
				return
			}
			t.Cleanup(func() { c.Close() })
		}
	}()
	config := fmt.Sprintf(`{"log": {"level": "error"}, "outbounds": [%s, %s]}`,
		ssNode("n-live", ssServer(t)), ssNode("n-silent", silent.Addr().(*net.TCPAddr).Port))

	start := time.Now()
	got := probeOne(t, &ProbeRequest{
		NodesJSON: config, URL: fixture.URL + "/generate_204", TimeoutMs: 8000, Concurrency: 2,
		TimeoutsJSON: `{"n-silent": 400}`,
	})
	if wall := time.Since(start); wall > 3*time.Second {
		t.Fatalf("probe took %v; the per-node timeout was ignored", wall)
	}
	if !got["n-live"].OK || got["n-silent"].OK {
		t.Fatalf("results = %+v", got)
	}
}

func TestProbeConfigIPv4Only(t *testing.T) {
	nodes := `{"outbounds": [{"type": "trojan", "tag": "n-a", "server": "a.example.com", "server_port": 443, "password": "synthetic",
		"domain_resolver": {"server": "local", "strategy": "prefer_ipv4"}}]}`
	var parsed struct {
		DNS   struct{ Strategy string } `json:"dns"`
		Route struct {
			Resolver any `json:"default_domain_resolver"`
		} `json:"route"`
		Outbound []struct {
			Resolver struct{ Strategy string } `json:"domain_resolver"`
		} `json:"outbounds"`
	}
	config, err := probeConfig(nodes, "192.0.2.10", true)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(config, &parsed); err != nil {
		t.Fatal(err)
	}
	resolver, _ := parsed.Route.Resolver.(map[string]any)
	if parsed.DNS.Strategy != "ipv4_only" || resolver["strategy"] != "ipv4_only" || parsed.Outbound[0].Resolver.Strategy != "ipv4_only" {
		t.Fatalf("probe config = %s", config)
	}
	config, err = probeConfig(nodes, "192.0.2.10", false)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(config), "ipv4_only") {
		t.Fatalf("dual-stack probe config forces ipv4_only: %s", config)
	}
}

func TestThroughputCapsAndCounts(t *testing.T) {
	payload := strings.Repeat("x", 65536)
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/__down" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = io.WriteString(w, payload)
	}))
	t.Cleanup(fixture.Close)
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	t.Cleanup(cancel)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newProbePlatform(nil, "", 0))
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(fmt.Sprintf(`{"log": {"level": "error"}, "outbounds": [%s]}`, ssNode("n-tp", ssServer(t)))))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	ob, _ := instance.Outbound().Outbound("n-tp")

	if r := throughputHTTP(ctx, ob, fixture.URL+"/__down", 65536, 3*time.Second); !r.OK || r.Bytes != 65536 {
		t.Fatalf("full = %+v", r)
	}
	if r := throughputHTTP(ctx, ob, fixture.URL+"/__down", 16384, 3*time.Second); !r.OK || r.Bytes != 16384 {
		t.Fatalf("capped = %+v", r)
	}
	if r := throughputHTTP(ctx, ob, fixture.URL+"/missing", 65536, 3*time.Second); r.OK || r.Code != http.StatusNotFound {
		t.Fatalf("404 = %+v", r)
	}
	if _, err := ThroughputOutbound(nil, "n-tp", fixture.URL+"/__down", 65536, 1000); err == nil {
		t.Fatal("ThroughputOutbound without a command server succeeded")
	}
}
