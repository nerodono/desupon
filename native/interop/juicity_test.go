package interop

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/juicity/juicity/server"
	"github.com/rs/zerolog"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const (
	juicityUUID     = "00000000-0000-4000-8000-0000000000a1"
	juicityPassword = "synthetic-juicity"
	juicitySNI      = "juicity.example.com"
)

// startJuicity runs the reference server on loopback and returns its port and the certificate chain hash its links pin.
func startJuicity(t *testing.T) (int, []byte) {
	t.Helper()
	cert := selfSigned(t, juicitySNI)
	dir := t.TempDir()
	certPath, keyPath := filepath.Join(dir, "cert.pem"), filepath.Join(dir, "key.pem")
	key, err := x509.MarshalECPrivateKey(cert.PrivateKey.(*ecdsa.PrivateKey))
	if err != nil {
		t.Fatal(err)
	}
	writePEM(t, certPath, "CERTIFICATE", cert.Certificate[0])
	writePEM(t, keyPath, "EC PRIVATE KEY", key)
	logger := zerolog.Nop()
	srv, err := server.New(&server.Options{
		Logger:            &logger,
		Users:             map[string]string{juicityUUID: juicityPassword},
		Certificate:       certPath,
		PrivateKey:        keyPath,
		CongestionControl: "bbr",
	})
	if err != nil {
		t.Fatal(err)
	}
	// Serve binds the address itself and has no Close; the free port is released just before it binds.
	probe, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.LocalAddr().(*net.UDPAddr).Port
	_ = probe.Close()
	go func() { _ = srv.Serve(fmt.Sprintf("127.0.0.1:%d", port)) }()
	time.Sleep(100 * time.Millisecond)
	leaf := sha256.Sum256(cert.Certificate[0])
	return port, leaf[:]
}

func writePEM(t *testing.T, path, kind string, der []byte) {
	t.Helper()
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: kind, Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
}

func juicityNode(port int, extra map[string]any) string {
	node := map[string]any{
		"type": "juicity", "tag": "juicity", "server": "127.0.0.1", "server_port": port,
		"uuid": juicityUUID, "password": juicityPassword,
		"tls": map[string]any{"enabled": true, "server_name": juicitySNI},
	}
	for k, v := range extra {
		node[k] = v
	}
	out, _ := json.Marshal(node)
	return string(out)
}

func juicityConfig(node string) string {
	return fmt.Sprintf(`{"log":{"level":"error"},"outbounds":[%s,{"type":"direct","tag":"direct"}]}`, node)
}

func TestJuicityInterop(t *testing.T) {
	fixture := newFixture(t)
	port, chain := startJuicity(t)
	insecure := map[string]any{"enabled": true, "server_name": juicitySNI, "insecure": true}
	cases := []struct {
		name  string
		extra map[string]any
		ok    bool
	}{
		{"pinned base64url", map[string]any{"pinned_certchain_sha256": base64.URLEncoding.EncodeToString(chain)}, true},
		{"pinned hex", map[string]any{"pinned_certchain_sha256": hex.EncodeToString(chain)}, true},
		{"insecure", map[string]any{"tls": insecure}, true},
		{"cubic", map[string]any{"tls": insecure, "congestion_control": "cubic"}, true},
		{"unverified certificate", nil, false},
		{"wrong pin", map[string]any{"pinned_certchain_sha256": base64.StdEncoding.EncodeToString(make([]byte, 32))}, false},
		{"wrong password", map[string]any{"tls": insecure, "password": "synthetic-other"}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := probeOne(t, juicityConfig(juicityNode(port, tc.extra)), fixture.URL+"/generate_204", 4000)
			if r.OK != tc.ok {
				t.Fatalf("probe = %+v, want ok=%v", r, tc.ok)
			}
		})
	}
}

func TestJuicityUDPInterop(t *testing.T) {
	port, chain := startJuicity(t)
	echo, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = echo.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := echo.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = echo.WriteTo(buf[:n], addr)
		}
	}()
	instance := startBox(t, juicityConfig(juicityNode(port, map[string]any{"pinned_certchain_sha256": base64.URLEncoding.EncodeToString(chain)})))
	out, ok := instance.Outbound().Outbound("juicity")
	if !ok {
		t.Fatal("juicity outbound missing")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := out.DialContext(ctx, N.NetworkUDP, M.ParseSocksaddr(echo.LocalAddr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	for i := range 3 {
		msg := fmt.Sprintf("synthetic datagram %d", i)
		if _, err := conn.Write([]byte(msg)); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 64)
		n, err := conn.Read(buf)
		if err != nil {
			t.Fatal(err)
		}
		if string(buf[:n]) != msg {
			t.Fatalf("echo = %q, want %q", buf[:n], msg)
		}
	}
}
