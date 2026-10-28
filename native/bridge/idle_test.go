package bridge

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// A live check of a spare node must release its QUIC session, while checks of the selected node
// must leave the core's idle policy in charge. Keeping a spare session alive sends QUIC keepalives.
func TestLiveChecksRespectQUICIdlePolicy(t *testing.T) {
	for _, throughput := range []bool{false, true} {
		for _, selected := range []bool{false, true} {
			t.Run(fmt.Sprintf("throughput=%v/selected=%v", throughput, selected), func(t *testing.T) {
				requests := make(chan string, 2)
				fixture := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					requests <- r.RemoteAddr
					if throughput {
						_, _ = w.Write([]byte(strings.Repeat("x", 1024)))
					} else {
						w.WriteHeader(http.StatusNoContent)
					}
				}))
				t.Cleanup(fixture.Close)
				probeRootPool = x509.NewCertPool()
				probeRootPool.AddCert(fixture.Certificate())
				t.Cleanup(func() { probeRootPool = nil })

				// The same test certificate serves the local Hysteria2 server and its HTTPS destination.
				certificate := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: fixture.Certificate().Raw})
				key, err := x509.MarshalPKCS8PrivateKey(fixture.TLS.Certificates[0].PrivateKey)
				if err != nil {
					t.Fatal(err)
				}
				tlsJSON, err := json.Marshal(map[string]any{
					"enabled": true, "certificate": string(certificate),
					"key": string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key})),
				})
				if err != nil {
					t.Fatal(err)
				}
				listener, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				port := listener.LocalAddr().(*net.UDPAddr).Port
				_ = listener.Close()
				startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "inbounds": [{"type": "hysteria2", "listen": "127.0.0.1", "listen_port": %d,
    "users": [{"name": "test", "password": "synthetic"}], "tls": %s}],
  "outbounds": [{"type": "direct", "tag": "direct"}]
}`, port, tlsJSON))
				chosen := "direct"
				if selected {
					chosen = "hy2"
				}
				client := startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "outbounds": [
    {"type": "selector", "tag": "proxy", "outbounds": ["direct", "hy2"], "default": %q},
    {"type": "hysteria2", "tag": "hy2", "server": "127.0.0.1", "server_port": %d,
      "password": "synthetic", "tls": {"enabled": true, "insecure": true}, "detour": "capture"},
    {"type": "probe-socket-capture", "tag": "capture"},
    {"type": "direct", "tag": "direct"}
  ],
  "route": {"final": "proxy"}
}`, chosen, port))
				dialer, _ := client.Outbound().Outbound("hy2")
				capture, _ := client.Outbound().Outbound("capture")
				if throughput {
					if r := throughputHTTP(context.Background(), dialer, fixture.URL, 1024, 5*time.Second); !r.OK || r.Bytes != 1024 {
						t.Fatalf("throughput = %+v", r)
					}
				} else {
					if r := probeHTTP(context.Background(), dialer, fixture.URL, 5*time.Second); !r.OK || !r.Warm {
						t.Fatalf("probe = %+v, want success and a warm measurement", r)
					}
					first, second := <-requests, <-requests
					if first != second {
						t.Fatalf("warm probe redialed HTTPS: %s then %s", first, second)
					}
				}
				opened := capture.(*probeSocketCapture).opened
				var conn *capturedProbeSocket
				select {
				case conn = <-opened:
				default:
					t.Fatal("check never opened a QUIC transport")
				}
				select {
				case <-opened:
					t.Fatal("check opened more than one QUIC session")
				default:
				}
				if selected {
					select {
					case <-conn.closed:
						t.Fatal("check closed the selected node's reusable QUIC session")
					default:
					}
				} else {
					select {
					case <-conn.closed:
					case <-time.After(time.Second):
						t.Fatal("unselected node kept its idle QUIC session after the check")
					}
				}
			})
		}
	}
}

func init() {
	include.RegisterExternalOutbound(func(registry *outbound.Registry) {
		outbound.Register[option.DialerOptions](registry, "probe-socket-capture", newProbeSocketCapture)
	})
}

// This direct detour observes the real QUIC UDP socket without replacing the protocol client.
type probeSocketCapture struct {
	outbound.Adapter
	opened chan *capturedProbeSocket
}

func newProbeSocketCapture(_ context.Context, _ adapter.Router, _ log.ContextLogger, tag string, options option.DialerOptions) (adapter.Outbound, error) {
	return &probeSocketCapture{
		Adapter: outbound.NewAdapterWithDialerOptions("probe-socket-capture", tag, []string{N.NetworkTCP, N.NetworkUDP}, options),
		opened:  make(chan *capturedProbeSocket, 4),
	}, nil
}

func (c *probeSocketCapture) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	conn, err := N.SystemDialer.DialContext(ctx, network, destination)
	if err != nil {
		return nil, err
	}
	captured := &capturedProbeSocket{Conn: conn, closed: make(chan struct{})}
	c.opened <- captured
	return captured, nil
}

func (c *probeSocketCapture) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return N.SystemDialer.ListenPacket(ctx, destination)
}

type capturedProbeSocket struct {
	net.Conn
	closed chan struct{}
	once   sync.Once
}

func (c *capturedProbeSocket) SyscallConn() (syscall.RawConn, error) {
	return c.Conn.(syscall.Conn).SyscallConn()
}

func (c *capturedProbeSocket) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { close(c.closed) })
	return err
}
