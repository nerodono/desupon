package bridge

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"sort"
	"strings"
	"sync/atomic"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"golang.org/x/crypto/curve25519"
)

type countingProtector struct{ calls atomic.Int32 }

func (p *countingProtector) Protect(int32) bool {
	p.calls.Add(1)
	return true
}

func newFixture(t *testing.T) *httptest.Server {
	t.Helper()
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/generate_204" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_, _ = w.Write([]byte("portal"))
	}))
	t.Cleanup(fixture.Close)
	return fixture
}

func startBox(t *testing.T, config string) *box.Box {
	t.Helper()
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	t.Cleanup(cancel)
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
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
	return instance
}

func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

// probeSetup starts a shadowsocks server box and returns a probe config with one live and one dead node.
func probeSetup(t *testing.T) string {
	t.Helper()
	ssPort := freePort(t)
	startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "inbounds": [{"type": "shadowsocks", "tag": "ss-in", "listen": "127.0.0.1", "listen_port": %d, "method": "aes-128-gcm", "password": "synthetic"}],
  "outbounds": [{"type": "direct", "tag": "direct"}]
}`, ssPort))
	return fmt.Sprintf(`{
  "log": {"level": "error"},
  "outbounds": [
    {"type": "shadowsocks", "tag": "n-live", "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": "synthetic"},
    {"type": "shadowsocks", "tag": "n-dead", "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": "synthetic"},
    {"type": "direct", "tag": "direct"}
  ]
}`, ssPort, freePort(t))
}

func runProbe(t *testing.T, config, url string, protector Protector) map[string]probeNodeResult {
	t.Helper()
	out, err := Probe(&ProbeRequest{NodesJSON: config, URL: url, DNSCSV: "192.0.2.53", TimeoutMs: 3000, Concurrency: 2}, protector)
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
	if _, ok := byTag["direct"]; ok {
		t.Fatal("direct outbound was probed")
	}
	return byTag
}

func TestProbeStrict204(t *testing.T) {
	fixture := newFixture(t)
	config := probeSetup(t)
	protector := &countingProtector{}

	got := runProbe(t, config, fixture.URL+"/generate_204", protector)
	if r := got["n-live"]; !r.OK || r.Code != http.StatusNoContent {
		t.Fatalf("n-live = %+v, want ok 204", r)
	}
	if r := got["n-dead"]; r.OK || r.Stage != StageTransport {
		t.Fatalf("n-dead = %+v, want transport failure", r)
	}
	if protector.calls.Load() == 0 {
		t.Fatal("probe sockets bypassed the protector")
	}

	got = runProbe(t, config, fixture.URL+"/portal", protector)
	if r := got["n-live"]; r.OK || r.Stage != StageGet204 || r.Code != http.StatusOK {
		t.Fatalf("n-live on a 200 page = %+v, want get204 failure with code 200", r)
	}
}

func TestProbeConfigRejectsListenersAndSharedState(t *testing.T) {
	for name, config := range map[string]string{
		"inbounds":            `{"inbounds": []}`,
		"experimental":        `{"experimental": {}}`,
		"services":            `{"services": []}`,
		"log.output":          `{"log": {"output": "box.log"}}`,
		"singleton outbound":  `{"outbounds": [{"type": "freeturn", "tag": "ft-1"}]}`,
		"singleton endpoint":  `{"endpoints": [{"type": "freeturn", "tag": "ft-1"}]}`,
		"detour to singleton": `{"outbounds": [{"type": "freeturn", "tag": "ft-1"}], "endpoints": [{"type": "wireguard", "tag": "turn-1", "detour": "ft-1"}]}`,
		"no underlying dns":   `{"outbounds": []}`,
	} {
		if _, err := probeConfig(config, "", false); err == nil {
			t.Errorf("probeConfig accepted %s", name)
		}
	}
	config, err := probeConfig(`{"log": {"level": "error"}, "outbounds": []}`, "fe80::1, 192.0.2.10", false)
	if err != nil {
		t.Fatal(err)
	}
	var parsed struct {
		DNS struct {
			Servers []struct{ Server string } `json:"servers"`
		} `json:"dns"`
		Route struct {
			AutoDetect bool   `json:"auto_detect_interface"`
			Resolver   string `json:"default_domain_resolver"`
		} `json:"route"`
	}
	if err := json.Unmarshal(config, &parsed); err != nil {
		t.Fatal(err)
	}
	if !parsed.Route.AutoDetect || parsed.Route.Resolver != "local" || len(parsed.DNS.Servers) != 1 || parsed.DNS.Servers[0].Server != "192.0.2.10" {
		t.Fatalf("probe config = %s", config)
	}
}

func wgKeyPair(t *testing.T) (string, string) {
	t.Helper()
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		t.Fatal(err)
	}
	priv[0] &= 248
	priv[31] = priv[31]&127 | 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(priv[:]), base64.StdEncoding.EncodeToString(pub)
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	return pc.LocalAddr().(*net.UDPAddr).Port
}

// TestProbeWireGuardNode: WireGuard sends nothing while the router's network is paused, which a nil default interface causes.
func TestProbeWireGuardNode(t *testing.T) {
	fixture := newFixture(t)
	serverPriv, serverPub := wgKeyPair(t)
	clientPriv, clientPub := wgKeyPair(t)
	wgPort := freeUDPPort(t)
	startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "endpoints": [{"type": "wireguard", "tag": "wg-srv", "listen_port": %d, "mtu": 1280, "address": ["10.9.0.1/32"],
    "private_key": %q, "peers": [{"public_key": %q, "allowed_ips": ["10.9.0.2/32"]}]}],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {"final": "direct"}
}`, wgPort, serverPriv, clientPub))
	config := fmt.Sprintf(`{
  "log": {"level": "error"},
  "endpoints": [{"type": "wireguard", "tag": "n-wg", "mtu": 1280, "address": ["10.9.0.2/32"], "private_key": %q,
    "peers": [{"address": "127.0.0.1", "port": %d, "public_key": %q, "allowed_ips": ["0.0.0.0/0"]}]}]
}`, clientPriv, wgPort, serverPub)
	// The server endpoint delivers traffic for its own address to loopback; gVisor drops a loopback destination itself.
	url := strings.Replace(fixture.URL, "127.0.0.1", "10.9.0.1", 1) + "/generate_204"
	protector := &countingProtector{}
	out, err := Probe(&ProbeRequest{NodesJSON: config, URL: url, DNSCSV: "192.0.2.53", InterfaceName: "wlan0", InterfaceIndex: 30, TimeoutMs: 8000}, protector)
	if err != nil {
		t.Fatal(err)
	}
	var results []probeNodeResult
	if err := json.Unmarshal([]byte(out), &results); err != nil {
		t.Fatal(err)
	}
	if len(results) != 1 || results[0].Tag != "n-wg" || !results[0].OK {
		t.Fatalf("WireGuard probe = %s, want n-wg ok 204", out)
	}
	if protector.calls.Load() == 0 {
		t.Fatal("WireGuard socket bypassed the protector")
	}
}

func TestProbeResultKeepsNon204Answer(t *testing.T) {
	r := probeNodeResult{Stage: StageGet204, Code: http.StatusOK, BodyLen: 6, RttMs: 12, Error: "unexpected response 200 OK"}.result()
	if r.OK || r.Stage != StageGet204 || r.Code != http.StatusOK || r.BodyLen != 6 || r.RttMs != 12 || r.Error == "" {
		t.Fatalf("result = %+v", r)
	}
}

func TestProbeOutboundWithoutService(t *testing.T) {
	if _, err := ProbeOutbound(nil, "n-live", "http://192.0.2.1/generate_204", 1000); err == nil {
		t.Fatal("ProbeOutbound without a command server succeeded")
	}
}

func TestMaskSecrets(t *testing.T) {
	for _, tc := range []struct{ in, leak string }{
		{`IpcSet: private_key=6f1c0e2a9b3d4c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6`, "6f1c0e2a"},
		{`uuid 0f8e1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b`, "0f8e1a2b"},
		{`dial ss://user:pw@host`, "user:pw"},
		{`password: hunter2`, "hunter2"},
		{`pub YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=`, "YAnz5TF"},
		{`{"private_key": "YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="}`, "YAnz5TF"},
		{`dial failed: https://vk.ru/call/join/Zx9QwErTyUiOp1234567890AbCdEfGhIjKlMnOpQrStUv`, "Zx9QwErT"},
		{`link=https://vk.com/call/join/abcDEF_123-xyz`, "abcDEF_123"},
		{`links: ["https://example.org/call/join/shortCode1?x=1"]`, "shortCode1"},
		{`code Zx9QwErTyUiOp1234567890AbCdEfGhIjKlMnOpQrStUv rejected`, "Zx9QwErT"},
		{`anon token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJlLXN5bnRoZXRpYw`, "eyJzdWIi"},
		{`clientId=0123456789abcdef`, "0123456789abcdef"},
	} {
		if out := maskSecrets(tc.in); strings.Contains(out, tc.leak) {
			t.Errorf("maskSecrets(%q) leaks %q: %s", tc.in, tc.leak, out)
		}
	}
	const plain = `probe transport: dial tcp 192.0.2.1:443: i/o timeout (n-ab12)`
	if out := maskSecrets(plain); out != plain {
		t.Errorf("maskSecrets altered a secret-free message: %s", out)
	}
}

func TestProbeSkipsChainHops(t *testing.T) {
	fixture := newFixture(t)
	ssPort := freePort(t)
	startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "inbounds": [{"type": "shadowsocks", "tag": "ss-in", "listen": "127.0.0.1", "listen_port": %d, "method": "aes-128-gcm", "password": "synthetic"}],
  "outbounds": [{"type": "direct", "tag": "direct"}]
}`, ssPort))
	ss := func(tag, detour string) string {
		s := fmt.Sprintf(`{"type": "shadowsocks", "tag": %q, "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": "synthetic"`, tag, ssPort)
		if detour != "" {
			s += fmt.Sprintf(`, "detour": %q`, detour)
		}
		return s + "}"
	}
	config := fmt.Sprintf(`{"log": {"level": "error"}, "outbounds": [%s, %s, %s, %s, %s]}`,
		ss("n-chain", "n-chain-1"), ss("n-chain-1", "n-chain-2"), ss("n-chain-2", ""), ss("n-solo-1", ""), ss("n-solo-4", ""))

	got := runProbe(t, config, fixture.URL+"/generate_204", &countingProtector{})
	var tags []string
	for tag := range got {
		tags = append(tags, tag)
	}
	sort.Strings(tags)
	if want := []string{"n-chain", "n-solo-1", "n-solo-4"}; !slices.Equal(tags, want) {
		t.Fatalf("probed %v, want %v", tags, want)
	}
	if r := got["n-chain"]; !r.OK {
		t.Fatalf("n-chain = %+v, want ok through its hops", r)
	}
}
