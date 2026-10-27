// Package interop runs the core's outbounds against reference server implementations.
package interop

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"

	"github.com/desupon/core/bridge"
)

type probeResult struct {
	Tag   string `json:"tag"`
	OK    bool   `json:"ok"`
	Stage string `json:"stage"`
	Code  int    `json:"code"`
	Error string `json:"error"`
}

func newFixture(t *testing.T) *httptest.Server {
	t.Helper()
	fixture := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(fixture.Close)
	return fixture
}

func selfSigned(t *testing.T, name string) tls.Certificate {
	t.Helper()
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: name},
		DNSNames:     []string{name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv}
}

// probeOne runs the app's probe path on a config with exactly one proxy node and returns its result.
func probeOne(t *testing.T, config, url string, timeoutMs int32) probeResult {
	t.Helper()
	out, err := bridge.Probe(&bridge.ProbeRequest{NodesJSON: config, URL: url, DNSCSV: "192.0.2.53", TimeoutMs: timeoutMs}, nil)
	if err != nil {
		t.Fatal(err)
	}
	var results []probeResult
	if err := json.Unmarshal([]byte(out), &results); err != nil {
		t.Fatal(err)
	}
	if len(results) != 1 {
		t.Fatalf("probe returned %d results: %s", len(results), out)
	}
	return results[0]
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
