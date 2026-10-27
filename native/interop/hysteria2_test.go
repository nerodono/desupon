package interop

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"testing"

	"github.com/apernet/hysteria/core/v2/server"
	"github.com/apernet/hysteria/extras/v2/obfs"
)

const (
	hy2Password   = "synthetic-auth"
	hy2ObfsSecret = "synthetic-obfs"
)

type passwordAuth string

func (p passwordAuth) Authenticate(_ net.Addr, auth string, _ uint64) (bool, string) {
	return auth == string(p), "user"
}

// startHysteria2 runs the reference server on loopback UDP behind wrap and returns its port.
func startHysteria2(t *testing.T, wrap func(net.PacketConn) (net.PacketConn, error)) int {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := conn.LocalAddr().(*net.UDPAddr).Port
	wrapped, err := wrap(conn)
	if err != nil {
		t.Fatal(err)
	}
	srv, err := server.NewServer(&server.Config{
		TLSConfig:     server.TLSConfig{Certificates: []tls.Certificate{selfSigned(t, "hy2.example.com")}},
		Conn:          wrapped,
		Authenticator: passwordAuth(hy2Password),
	})
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve() }()
	t.Cleanup(func() { _ = srv.Close() })
	return port
}

func hysteria2Node(port int, obfsBlock map[string]any) string {
	node := map[string]any{
		"type": "hysteria2", "tag": "hy2", "server": "127.0.0.1", "server_port": port,
		"password": hy2Password,
		"tls":      map[string]any{"enabled": true, "server_name": "hy2.example.com", "insecure": true},
	}
	if obfsBlock != nil {
		node["obfs"] = obfsBlock
	}
	out, _ := json.Marshal(node)
	return string(out)
}

func geckoServer(minSize, maxSize int) func(net.PacketConn) (net.PacketConn, error) {
	return func(c net.PacketConn) (net.PacketConn, error) {
		return obfs.WrapPacketConnGecko(c, obfs.GeckoOptions{Password: []byte(hy2ObfsSecret), MinPacketSize: minSize, MaxPacketSize: maxSize})
	}
}

func salamanderServer(c net.PacketConn) (net.PacketConn, error) {
	return obfs.WrapPacketConnSalamander(c, []byte(hy2ObfsSecret))
}

func TestHysteria2ObfsInterop(t *testing.T) {
	fixture := newFixture(t)
	cases := []struct {
		name   string
		server func(net.PacketConn) (net.PacketConn, error)
		obfs   map[string]any
		ok     bool
	}{
		{"plain", func(c net.PacketConn) (net.PacketConn, error) { return c, nil }, nil, true},
		{"salamander", salamanderServer, map[string]any{"type": "salamander", "password": hy2ObfsSecret}, true},
		{"gecko defaults", geckoServer(0, 0), map[string]any{"type": "gecko", "password": hy2ObfsSecret}, true},
		{"gecko sizes", geckoServer(300, 1400), map[string]any{"type": "gecko", "password": hy2ObfsSecret, "min_packet_size": 300, "max_packet_size": 1400}, true},
		{"gecko server large packets", geckoServer(1500, 2048), map[string]any{"type": "gecko", "password": hy2ObfsSecret}, true},
		{"gecko client large packets", geckoServer(0, 0), map[string]any{"type": "gecko", "password": hy2ObfsSecret, "min_packet_size": 1500, "max_packet_size": 2048}, true},
		{"gecko client sizes only", geckoServer(0, 0), map[string]any{"type": "gecko", "password": hy2ObfsSecret, "min_packet_size": 200, "max_packet_size": 1350}, true},
		{"gecko wrong password", geckoServer(0, 0), map[string]any{"type": "gecko", "password": "synthetic-other"}, false},
		{"gecko client, salamander server", salamanderServer, map[string]any{"type": "gecko", "password": hy2ObfsSecret}, false},
		{"salamander client, gecko server", geckoServer(0, 0), map[string]any{"type": "salamander", "password": hy2ObfsSecret}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			port := startHysteria2(t, tc.server)
			config := fmt.Sprintf(`{"log":{"level":"error"},"outbounds":[%s,{"type":"direct","tag":"direct"}]}`, hysteria2Node(port, tc.obfs))
			r := probeOne(t, config, fixture.URL+"/generate_204", 4000)
			if r.OK != tc.ok {
				t.Fatalf("probe = %+v, want ok=%v", r, tc.ok)
			}
		})
	}
}
