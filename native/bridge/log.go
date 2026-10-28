package bridge

import (
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
)

// SetPlatformLogLevel suppresses unrequested diagnostics before formatting or sending them to Android.
// It applies to the running service and future reloads; console logging keeps its configured threshold.
func SetPlatformLogLevel(level int32) error {
	return onGoroutine(func() error {
		if level < int32(log.LevelPanic) || level > int32(log.LevelTrace) {
			return E.New("invalid platform log level")
		}
		log.SetPlatformLogLevel(log.Level(level))
		return nil
	})
}
