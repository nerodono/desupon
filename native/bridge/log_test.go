package bridge

import (
	"bytes"
	"context"
	"io"
	"testing"

	"github.com/sagernet/sing-box/log"
)

type platformLogRecorder struct {
	levels []log.Level
}

func (r *platformLogRecorder) WriteMessage(level log.Level, _ string) {
	r.levels = append(r.levels, level)
}

type countedLogText struct {
	formatted *int
}

func (c countedLogText) String() string {
	*c.formatted++
	return "diagnostic text"
}

func TestPlatformLogThreshold(t *testing.T) {
	t.Cleanup(func() { _ = SetPlatformLogLevel(int32(log.LevelNotice)) })
	setLevel := func(level log.Level) {
		t.Helper()
		if err := SetPlatformLogLevel(int32(level)); err != nil {
			t.Fatal(err)
		}
	}
	setLevel(log.LevelNotice)
	recorder := &platformLogRecorder{}
	factory := log.NewDefaultFactory(context.Background(), log.Formatter{}, io.Discard, "", recorder, false)
	factory.SetLevel(log.LevelWarn)
	if err := factory.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = factory.Close() })
	logger := factory.Logger()
	formatted := 0
	text := countedLogText{formatted: &formatted}
	for range 1000 {
		logger.Debug(text)
		logger.Info(text)
		logger.Trace(text)
	}
	if formatted != 0 || len(recorder.levels) != 0 {
		t.Fatalf("suppressed diagnostics formatted %d times, crossed platform writer %d times", formatted, len(recorder.levels))
	}
	logger.Notice(text)
	if formatted != 1 || len(recorder.levels) != 1 || recorder.levels[0] != log.LevelNotice {
		t.Fatal("default threshold dropped the service notice")
	}
	setLevel(log.LevelDebug)
	logger.Debug(text)
	logger.Trace(text)
	if formatted != 2 || len(recorder.levels) != 2 || recorder.levels[1] != log.LevelDebug {
		t.Fatal("explicit debug threshold did not take effect on the running logger")
	}
	setLevel(log.LevelTrace)
	logger.Trace(text)
	if formatted != 3 || recorder.levels[2] != log.LevelTrace {
		t.Fatal("explicit trace threshold did not take effect")
	}
	setLevel(log.LevelError)
	logger.Notice(text)
	logger.Error(text)
	if formatted != 4 || len(recorder.levels) != 4 || recorder.levels[3] != log.LevelError {
		t.Fatal("lowered threshold did not suppress diagnostics")
	}
	if SetPlatformLogLevel(-1) == nil || SetPlatformLogLevel(256) == nil {
		t.Fatal("invalid level accepted")
	}
}

func TestPlatformLogThresholdPreservesConsole(t *testing.T) {
	t.Cleanup(func() { _ = SetPlatformLogLevel(int32(log.LevelNotice)) })
	if err := SetPlatformLogLevel(int32(log.LevelError)); err != nil {
		t.Fatal(err)
	}
	var console bytes.Buffer
	recorder := &platformLogRecorder{}
	factory := log.NewDefaultFactory(context.Background(), log.Formatter{}, &console, "", recorder, false)
	factory.SetLevel(log.LevelDebug)
	if err := factory.Start(); err != nil {
		t.Fatal(err)
	}
	defer factory.Close()
	factory.Logger().Debug("console diagnostic")
	if !bytes.Contains(console.Bytes(), []byte("console diagnostic")) || len(recorder.levels) != 0 {
		t.Fatal("platform threshold changed console logging or forwarded a suppressed line")
	}
}
