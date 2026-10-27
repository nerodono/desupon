// Package bridge is Desupon's own gomobile surface next to libbox.
package bridge

import (
	"github.com/sagernet/sing-box/experimental/libbox"

	// Out-of-tree outbounds register themselves for libbox and the probe alike.
	_ "github.com/desupon/core/juicity"
)

// Protector excludes a socket from the VPN (VpnService.protect).
type Protector interface {
	Protect(fd int32) bool
}

func Versions() string {
	return "core " + libbox.Version()
}

// onGoroutine runs f off the calling Java thread: Go code entered from a small Java stack can overflow it.
func onGoroutine[T any](f func() T) T {
	done := make(chan T, 1)
	go func() { done <- f() }()
	return <-done
}

type result[T any] struct {
	value T
	err   error
}

func onGoroutineErr[T any](f func() (T, error)) (T, error) {
	r := onGoroutine(func() result[T] {
		v, err := f()
		return result[T]{v, err}
	})
	return r.value, r.err
}
