package bridge

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const realityTestServerName = "www.example.com"

// startRealityDest serves the TLS 1.3 site REALITY borrows its handshake from; ECDSA, since uTLS Chrome offers no Ed25519.
func startRealityDest(t *testing.T) int {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: realityTestServerName},
		DNSNames:     []string{realityTestServerName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: priv}},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go acceptLoop(ln, func(c net.Conn) {
		_ = c.(*tls.Conn).Handshake()
		_, _ = io.Copy(io.Discard, c)
	})
	return ln.Addr().(*net.TCPAddr).Port
}

func startEcho(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go acceptLoop(ln, func(c net.Conn) { _, _ = io.Copy(c, c) })
	return ln.Addr().String()
}

func acceptLoop(ln net.Listener, handle func(net.Conn)) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			handle(c)
		}()
	}
}

// wireTap relays to target and keeps every client-to-server read, so the test sees how the ClientHello hit the wire.
type wireTap struct {
	mu    sync.Mutex
	reads [][]byte
}

func startWireTap(t *testing.T, target int) (*wireTap, int) {
	t.Helper()
	tap := &wireTap{}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go acceptLoop(ln, func(c net.Conn) {
		up, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", target))
		if err != nil {
			return
		}
		defer up.Close()
		go func() { _, _ = io.Copy(c, up) }()
		buf := make([]byte, 64<<10)
		for {
			n, err := c.Read(buf)
			if n > 0 {
				tap.mu.Lock()
				tap.reads = append(tap.reads, append([]byte(nil), buf[:n]...))
				tap.mu.Unlock()
				if _, werr := up.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	})
	return tap, ln.Addr().(*net.TCPAddr).Port
}

// clientHello returns how many TLS records and socket reads carried the first ClientHello.
func (w *wireTap) clientHello(t *testing.T) (records, reads int) {
	t.Helper()
	w.mu.Lock()
	defer w.mu.Unlock()
	var stream []byte
	var readEnds []int
	for _, r := range w.reads {
		stream = append(stream, r...)
		readEnds = append(readEnds, len(stream))
	}
	var msg []byte
	for off := 0; off+5 <= len(stream); {
		if stream[off] != 0x16 {
			t.Fatalf("record %d has content type %#x before the ClientHello completed", records, stream[off])
		}
		end := off + 5 + int(binary.BigEndian.Uint16(stream[off+3:]))
		if end > len(stream) {
			break
		}
		msg = append(msg, stream[off+5:end]...)
		off = end
		records++
		if len(msg) >= 4 && len(msg) >= 4+int(msg[1])<<16|int(msg[2])<<8|int(msg[3]) {
			if msg[0] != 1 {
				t.Fatalf("first handshake message type %d, want ClientHello", msg[0])
			}
			for i, e := range readEnds {
				if e >= off {
					return records, i + 1
				}
			}
		}
	}
	t.Fatal("no complete ClientHello on the wire")
	return 0, 0
}

func TestRealityFragmentedHandshake(t *testing.T) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privateKey := base64.RawURLEncoding.EncodeToString(key.Bytes())
	publicKey := base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes())
	const uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
	const shortID = "0123456789abcdef"
	destPort := startRealityDest(t)
	echoAddr := startEcho(t)

	cases := []struct {
		name       string
		fragment   map[string]any
		minRecords int
		maxRecords int
		minReads   int
	}{
		{name: "none", fragment: nil, minRecords: 1, maxRecords: 1, minReads: 1},
		{name: "record", fragment: map[string]any{"record_fragment": true}, minRecords: 2, maxRecords: 64, minReads: 1},
		{name: "record+packet", fragment: map[string]any{"record_fragment": true, "fragment": true, "fragment_fallback_delay": "50ms"}, minRecords: 2, maxRecords: 64, minReads: 2},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			inPort := freePort(t)
			tap, tapPort := startWireTap(t, inPort)
			clientTLS := map[string]any{
				"enabled":     true,
				"server_name": realityTestServerName,
				"utls":        map[string]any{"enabled": true, "fingerprint": "chrome"},
				"reality":     map[string]any{"enabled": true, "public_key": publicKey, "short_id": shortID},
			}
			for k, v := range tc.fragment {
				clientTLS[k] = v
			}
			config := map[string]any{
				"log": map[string]any{"level": "error"},
				"inbounds": []any{map[string]any{
					"type": "vless", "tag": "in", "listen": "127.0.0.1", "listen_port": inPort,
					"users": []any{map[string]any{"uuid": uuid}},
					"tls": map[string]any{
						"enabled":     true,
						"server_name": realityTestServerName,
						"reality": map[string]any{
							"enabled":     true,
							"handshake":   map[string]any{"server": "127.0.0.1", "server_port": destPort},
							"private_key": privateKey,
							"short_id":    []any{shortID},
						},
					},
				}},
				"outbounds": []any{
					map[string]any{"type": "direct", "tag": "direct"},
					map[string]any{"type": "vless", "tag": "proxy", "server": "127.0.0.1", "server_port": tapPort, "uuid": uuid, "tls": clientTLS},
				},
			}
			raw, err := json.Marshal(config)
			if err != nil {
				t.Fatal(err)
			}
			instance := startBox(t, string(raw))
			proxy, ok := instance.Outbound().Outbound("proxy")
			if !ok {
				t.Fatal("proxy outbound missing")
			}

			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			conn, err := proxy.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddr(echoAddr))
			if err != nil {
				t.Fatalf("REALITY handshake: %v", err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			if _, err := conn.Write([]byte("ping")); err != nil {
				t.Fatal(err)
			}
			echo := make([]byte, 4)
			if _, err := io.ReadFull(conn, echo); err != nil || string(echo) != "ping" {
				t.Fatalf("echo through REALITY = %q, %v", echo, err)
			}

			records, reads := tap.clientHello(t)
			if records < tc.minRecords || records > tc.maxRecords {
				t.Fatalf("ClientHello in %d records, want %d..%d", records, tc.minRecords, tc.maxRecords)
			}
			if reads < tc.minReads {
				t.Fatalf("ClientHello in %d reads, want at least %d", reads, tc.minReads)
			}
		})
	}
}
