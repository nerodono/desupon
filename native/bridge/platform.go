package bridge

import (
	"context"
	"net"
	"net/netip"
	"os"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

// probePlatform lets a TUN-less box start on Android: without a platform the router reads packages.xml, which apps cannot.
type probePlatform struct {
	protector Protector
	iface     control.Interface
}

// newProbePlatform reports one up interface as the default: without it the router pauses the network and WireGuard never sends.
func newProbePlatform(protector Protector, name string, index int32) *probePlatform {
	if name == "" {
		name = "probe0"
	}
	return &probePlatform{
		protector: protector,
		iface:     control.Interface{Index: int(index), MTU: 1500, Name: name, Flags: net.FlagUp | net.FlagRunning},
	}
}

var _ adapter.PlatformInterface = (*probePlatform)(nil)

func (p *probePlatform) Initialize(adapter.NetworkManager) error { return nil }

// Always true so sockets are never bound by interface name; the reported interface may not exist locally.
func (p *probePlatform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *probePlatform) AutoDetectInterfaceControl(fd int) error {
	if p.protector == nil {
		return nil
	}
	if !p.protector.Protect(int32(fd)) {
		return E.New("protect socket failed")
	}
	return nil
}

func (p *probePlatform) BindInterfaceControl(int, string) error { return os.ErrInvalid }

func (p *probePlatform) UsePlatformInterface() bool { return false }

func (p *probePlatform) OpenInterface(*tun.Options, option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}

func (p *probePlatform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (p *probePlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }

func (p *probePlatform) CreateDefaultInterfaceMonitor(logger.Logger) tun.DefaultInterfaceMonitor {
	return probeMonitor{iface: p.iface}
}

// Platform interfaces would switch the default dialer to per-interface binding, which the synthetic interface cannot serve.
func (p *probePlatform) UsePlatformNetworkInterfaces() bool { return false }

func (p *probePlatform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}

func (p *probePlatform) UnderNetworkExtension() bool              { return false }
func (p *probePlatform) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *probePlatform) ClearDNSCache()                           {}
func (p *probePlatform) RequestPermissionForWIFIState() error     { return nil }

func (p *probePlatform) ReadWIFIState(context.Context) adapter.WIFIState { return adapter.WIFIState{} }

func (p *probePlatform) UsePlatformConnectionOwnerFinder() bool { return false }

func (p *probePlatform) FindConnectionOwner(*adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (p *probePlatform) UsePlatformWIFIMonitor() bool                 { return false }
func (p *probePlatform) UsePlatformNotification() bool                { return false }
func (p *probePlatform) SendNotification(*adapter.Notification) error { return nil }
func (p *probePlatform) CancelNotification(string, int32) error       { return nil }
func (p *probePlatform) MyInterfaceAddress() []netip.Addr             { return nil }
func (p *probePlatform) UsePlatformNeighborResolver() bool            { return false }
func (p *probePlatform) StartNeighborMonitor(adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}
func (p *probePlatform) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (p *probePlatform) UsePlatformShell() bool                                    { return false }
func (p *probePlatform) CheckPlatformShell() error                                 { return os.ErrInvalid }

func (p *probePlatform) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (p *probePlatform) LookupUser(string) (*adapter.PlatformUser, error) { return nil, os.ErrInvalid }
func (p *probePlatform) LookupSFTPServer() (string, error)                { return "", os.ErrInvalid }
func (p *probePlatform) ReadSystemSSHHostKey() ([]byte, error)            { return nil, os.ErrInvalid }
func (p *probePlatform) TailscaleHostname() string                        { return "" }
func (p *probePlatform) UsePlatformBridge() bool                          { return false }

func (p *probePlatform) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

type probeMonitor struct {
	iface control.Interface
}

func (probeMonitor) Start() error                           { return nil }
func (probeMonitor) Close() error                           { return nil }
func (m probeMonitor) DefaultInterface() *control.Interface { return &m.iface }
func (probeMonitor) OverrideAndroidVPN() bool               { return false }
func (probeMonitor) AndroidVPNEnabled() bool                { return false }

func (probeMonitor) RegisterCallback(tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	return nil
}

func (probeMonitor) UnregisterCallback(*list.Element[tun.DefaultInterfaceUpdateCallback]) {}
func (probeMonitor) RegisterMyInterface(string)                                           {}
func (probeMonitor) MyInterfaces() []string                                               { return nil }
