package bridge

import (
	"testing"

	"github.com/sagernet/sing-box/protocol/group"
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
}
