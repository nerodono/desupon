package bridge

import (
	"context"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/protocol/group"
	E "github.com/sagernet/sing/common/exceptions"
	"google.golang.org/protobuf/types/known/emptypb"
)

// SelectOutbound switches a selector of the running service in-process; unlike the command client it cannot race a reconnect.
func SelectOutbound(server *libbox.CommandServer, groupTag, outboundTag string) error {
	return onGoroutine(func() error {
		b, err := runningBox(server)
		if err != nil {
			return maskErr(err)
		}
		return maskErr(selectIn(b, groupTag, outboundTag))
	})
}

// CloseConnections drops every connection of the running service.
func CloseConnections(server *libbox.CommandServer) error {
	return onGoroutine(func() error {
		if _, err := runningBox(server); err != nil {
			return maskErr(err)
		}
		_, err := server.StartedService.CloseAllConnections(context.Background(), &emptypb.Empty{})
		return maskErr(err)
	})
}

func runningBox(server *libbox.CommandServer) (*box.Box, error) {
	if server == nil || server.StartedService == nil {
		return nil, E.New("core: no command server")
	}
	instance := server.Instance()
	if instance == nil || instance.Box() == nil {
		return nil, E.New("core: service not started")
	}
	return instance.Box(), nil
}

func selectIn(b *box.Box, groupTag, outboundTag string) error {
	outbound, ok := b.Outbound().Outbound(groupTag)
	if !ok {
		return E.New("select: group not found: ", groupTag)
	}
	selector, ok := outbound.(*group.Selector)
	if !ok {
		return E.New("select: not a selector: ", groupTag)
	}
	if !selector.SelectOutbound(outboundTag) {
		return E.New("select: outbound not in group: ", outboundTag)
	}
	return nil
}
