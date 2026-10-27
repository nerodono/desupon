// Package juicity adds the juicity outbound to every registry the core builds.
package juicity

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"net"
	"os"

	sjuicity "github.com/exclavenetwork/sing-juicity"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	qtls "github.com/sagernet/sing-quic"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/gofrs/uuid/v5"
)

const Type = "juicity"

// Options: PinnedCertChainSHA256 is juicity's chain hash (base64 or hex); it replaces CA verification, as in the reference client.
type Options struct {
	option.DialerOptions
	option.ServerOptions
	UUID                  string `json:"uuid,omitempty"`
	Password              string `json:"password,omitempty"`
	CongestionControl     string `json:"congestion_control,omitempty"`
	PinnedCertChainSHA256 string `json:"pinned_certchain_sha256,omitempty"`
	option.OutboundTLSOptionsContainer
	option.QUICOptions
}

func init() {
	include.RegisterExternalOutbound(func(registry *outbound.Registry) {
		outbound.Register[Options](registry, Type, NewOutbound)
	})
}

var (
	_ adapter.InterfaceUpdateListener = (*Outbound)(nil)
	_ adapter.IdleConnectionKeeper    = (*Outbound)(nil)
	_ adapter.OutboundWithMultiplex   = (*Outbound)(nil)
)

type Outbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *sjuicity.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options Options) (adapter.Outbound, error) {
	if options.TLS == nil || !options.TLS.Enabled {
		return nil, C.ErrTLSRequired
	}
	tlsOptions := *options.TLS
	if tlsOptions.ALPN == nil {
		tlsOptions.ALPN = []string{"h3"}
	}
	tlsConfig, err := tls.NewSTDClient(ctx, logger, options.Server, tlsOptions)
	if err != nil {
		return nil, err
	}
	if options.PinnedCertChainSHA256 != "" {
		pin, err := decodePin(options.PinnedCertChainSHA256)
		if err != nil {
			return nil, err
		}
		std, err := tlsConfig.STDConfig()
		if err != nil {
			return nil, err
		}
		if std.VerifyPeerCertificate != nil {
			return nil, E.New("pinned_certchain_sha256 is conflict with certificate_public_key_sha256")
		}
		std.InsecureSkipVerify = true
		std.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if !bytes.Equal(certChainHash(rawCerts), pin) {
				return E.New("certificate chain hash mismatch")
			}
			return nil
		}
	}
	userUUID, err := uuid.FromString(options.UUID)
	if err != nil {
		return nil, E.Cause(err, "invalid uuid")
	}
	// The library leaves an empty name without any controller; the reference client defaults to BBR.
	congestion := options.CongestionControl
	if congestion == "" {
		congestion = "bbr"
	}
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	client, err := sjuicity.NewClient(sjuicity.ClientOptions{
		Context:       ctx,
		Dialer:        outboundDialer,
		ServerAddress: options.ServerOptions.Build(),
		TLSConfig:     tlsConfig,
		QUICOptions: qtls.QUICOptions{
			StreamReceiveWindow:     options.StreamReceiveWindow.Value(),
			ConnectionReceiveWindow: options.ConnectionReceiveWindow.Value(),
			MaxConcurrentStreams:    options.MaxConcurrentStreams,
			IdleTimeout:             options.IdleTimeout.Build(),
			KeepAlivePeriod:         options.KeepAlivePeriod.Build(),
			InitialPacketSize:       options.InitialPacketSize,
			DisablePathMTUDiscovery: options.DisablePathMTUDiscovery,
		},
		UUID:              userUUID,
		Password:          options.Password,
		CongestionControl: congestion,
	})
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:  logger,
		client:  client,
	}, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "outbound connection to ", destination)
		return h.client.DialConn(ctx, destination)
	case N.NetworkUDP:
		conn, err := h.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	h.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	return h.client.ListenPacket(ctx, destination)
}

func (h *Outbound) InterfaceUpdated(ctx context.Context) {
	_ = h.client.CloseWithError(E.New("network changed"))
}

func (h *Outbound) MultiplexEnabled() bool {
	return true
}

func (h *Outbound) SetKeepIdleConnections(keep bool) {
	h.client.SetKeepIdleConnections(keep)
}

func (h *Outbound) CloseIdleConnections() {
	h.client.CloseIdleConnections()
}

func (h *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	scope.Add(func() error {
		return h.client.CloseWithError(os.ErrClosed)
	})
	return nil
}

// certChainHash folds the SHA-256 of each certificate, leaf first, as juicity defines the pinned hash.
func certChainHash(rawCerts [][]byte) []byte {
	var chain []byte
	for _, cert := range rawCerts {
		sum := sha256.Sum256(cert)
		if chain == nil {
			chain = sum[:]
			continue
		}
		next := sha256.Sum256(append(chain, sum[:]...))
		chain = next[:]
	}
	return chain
}

// decodePin accepts the encodings juicity links carry; only a 32-byte result counts, so a hex pin never decodes as base64.
func decodePin(raw string) ([]byte, error) {
	for _, decode := range []func(string) ([]byte, error){
		base64.URLEncoding.DecodeString,
		base64.RawURLEncoding.DecodeString,
		base64.StdEncoding.DecodeString,
		base64.RawStdEncoding.DecodeString,
		hex.DecodeString,
	} {
		if pin, err := decode(raw); err == nil && len(pin) == sha256.Size {
			return pin, nil
		}
	}
	return nil, E.New("pinned_certchain_sha256 is not a base64 or hex SHA-256")
}
