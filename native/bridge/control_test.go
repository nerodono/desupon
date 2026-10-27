package bridge

import (
	"context"
	"net"
	"os"
	"slices"
	"sync"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/group"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func TestSelectOutboundSwitchesSelector(t *testing.T) {
	b := startBox(t, `{
  "log": {"level": "error"},
  "outbounds": [
    {"type": "selector", "tag": "proxy", "outbounds": ["n-a", "n-b"], "default": "n-a"},
    {"type": "direct", "tag": "n-a"},
    {"type": "direct", "tag": "n-b"},
    {"type": "direct", "tag": "direct"}
  ],
  "route": {"final": "proxy"}
}`)
	if err := selectIn(b, "proxy", "n-b"); err != nil {
		t.Fatal(err)
	}
	outbound, _ := b.Outbound().Outbound("proxy")
	if now := outbound.(*group.Selector).Now(); now != "n-b" {
		t.Fatalf("selected %q, want n-b", now)
	}
	if err := selectIn(b, "proxy", "n-missing"); err == nil {
		t.Fatal("selecting a non-member succeeded")
	}
	if err := selectIn(b, "direct", "n-a"); err == nil {
		t.Fatal("selecting on a non-selector succeeded")
	}
	if err := selectIn(b, "absent", "n-a"); err == nil {
		t.Fatal("selecting on a missing group succeeded")
	}
}

func TestResetOutboundResetsItsChainOnly(t *testing.T) {
	b := startBox(t, `{
  "log": {"level": "error"},
  "outbounds": [
    {"type": "selector", "tag": "proxy", "outbounds": ["n-a", "n-b"], "default": "n-a"},
    {"type": "reset-recorder", "tag": "n-a", "detour": "n-a-hop1"},
    {"type": "reset-recorder", "tag": "n-a-hop1", "detour": "n-a-hop2"},
    {"type": "reset-recorder", "tag": "n-a-hop2"},
    {"type": "reset-recorder", "tag": "n-b"},
    {"type": "direct", "tag": "direct"}
  ],
  "route": {"final": "proxy"}
}`)
	resets.take()
	if err := resetIn(b, "n-a"); err != nil {
		t.Fatal(err)
	}
	if got, want := resets.take(), []string{"n-a", "n-a-hop1", "n-a-hop2"}; !slices.Equal(got, want) {
		t.Fatalf("reset %v, want %v", got, want)
	}
	if err := resetIn(b, "n-missing"); err == nil {
		t.Fatal("resetting a missing outbound succeeded")
	}
}

func TestControlWithoutService(t *testing.T) {
	if err := SelectOutbound(nil, "proxy", "n-a"); err == nil {
		t.Fatal("SelectOutbound without a command server succeeded")
	}
	if err := CloseConnections(nil); err == nil {
		t.Fatal("CloseConnections without a command server succeeded")
	}
	if err := ResetNetwork(nil); err == nil {
		t.Fatal("ResetNetwork without a command server succeeded")
	}
	if err := ResetOutbound(nil, "n-a"); err == nil {
		t.Fatal("ResetOutbound without a command server succeeded")
	}
}

type resetLog struct {
	access sync.Mutex
	tags   []string
}

func (l *resetLog) add(tag string) {
	l.access.Lock()
	l.tags = append(l.tags, tag)
	l.access.Unlock()
}

func (l *resetLog) take() []string {
	l.access.Lock()
	defer l.access.Unlock()
	tags := l.tags
	l.tags = nil
	slices.Sort(tags)
	return tags
}

var resets resetLog

func init() {
	include.RegisterExternalOutbound(func(registry *outbound.Registry) {
		outbound.Register[option.DialerOptions](registry, "reset-recorder", newResetRecorder)
	})
}

// resetRecorder logs each interface update it gets.
type resetRecorder struct {
	outbound.Adapter
}

func newResetRecorder(_ context.Context, _ adapter.Router, _ log.ContextLogger, tag string, options option.DialerOptions) (adapter.Outbound, error) {
	return &resetRecorder{outbound.NewAdapterWithDialerOptions("reset-recorder", tag, []string{N.NetworkTCP, N.NetworkUDP}, options)}, nil
}

func (r *resetRecorder) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, os.ErrInvalid
}

func (r *resetRecorder) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (r *resetRecorder) InterfaceUpdated(context.Context) {
	resets.add(r.Tag())
}
