package bridge

import (
	"bufio"
	"net"
	"os"
	"strings"
	"testing"
)

type procSocket struct {
	proto, local, inode string
	listen              bool
}

// loopback reports 127.0.0.0/8, ::1 or ::ffff:127.0.0.0/104 in /proc/net's little-endian hex.
func (s procSocket) loopback() bool {
	ip := s.local[:strings.LastIndexByte(s.local, ':')]
	switch len(ip) {
	case 8:
		return strings.HasSuffix(ip, "7F")
	case 32:
		return ip == "00000000000000000000000001000000" ||
			strings.HasPrefix(ip, "0000000000000000FFFF0000") && strings.HasSuffix(ip, "7F")
	}
	return false
}

// ownSockets maps this process's socket inodes to /proc/net entries; UDP counts as listening when unconnected.
func ownSockets(t *testing.T) map[string]procSocket {
	t.Helper()
	inodes := map[string]bool{}
	fds, _ := os.ReadDir("/proc/self/fd")
	for _, fd := range fds {
		if link, err := os.Readlink("/proc/self/fd/" + fd.Name()); err == nil && strings.HasPrefix(link, "socket:[") {
			inodes[strings.TrimSuffix(strings.TrimPrefix(link, "socket:["), "]")] = true
		}
	}
	out := map[string]procSocket{}
	for _, proto := range []string{"tcp", "tcp6", "udp", "udp6"} {
		f, err := os.Open("/proc/net/" + proto)
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Scan()
		for sc.Scan() {
			fields := strings.Fields(sc.Text())
			if len(fields) < 10 || !inodes[fields[9]] {
				continue
			}
			listen := fields[3] == "0A"
			if strings.HasPrefix(proto, "udp") {
				listen = strings.Trim(fields[2], "0:") == ""
			}
			out[fields[9]] = procSocket{proto: proto, local: fields[1], inode: fields[9], listen: listen}
		}
		_ = f.Close()
	}
	return out
}

func newListeners(t *testing.T, before map[string]procSocket) []procSocket {
	t.Helper()
	var found []procSocket
	for _, s := range ownSockets(t) {
		if _, seen := before[s.inode]; seen || !s.listen {
			continue
		}
		if strings.HasPrefix(s.proto, "tcp") || s.loopback() {
			found = append(found, s)
		} else {
			t.Logf("unconnected wildcard UDP socket (outbound relay socket): %s %s", s.proto, s.local)
		}
	}
	return found
}

func TestOwnSocketsDetectsLoopbackListeners(t *testing.T) {
	before := ownSockets(t)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	found := map[string]bool{}
	for _, s := range newListeners(t, before) {
		found[s.proto] = true
	}
	if !found["tcp"] || !found["udp"] {
		t.Fatalf("detector missed loopback listeners: %v", found)
	}
	for local, want := range map[string]bool{
		"0100007F:1F90":                         true,
		"00000000000000000000000001000000:1F90": true,
		"0000000000000000FFFF00000100007F:1F90": true,
		"0000000000000000FFFF00000A00000A:1F90": false,
		"00000000000000000000000000000000:1F90": false,
		"0A00000A:1F90":                         false,
	} {
		if got := (procSocket{local: local}).loopback(); got != want {
			t.Errorf("loopback(%s) = %v, want %v", local, got, want)
		}
	}
}

// TestProbeOpensNoListeners samples the process sockets while the probe box is up.
func TestProbeOpensNoListeners(t *testing.T) {
	fixture := newFixture(t)
	config := probeSetup(t)
	before := ownSockets(t)
	var during []procSocket
	sampled := false
	probeStartedHook = func() {
		during = newListeners(t, before)
		sampled = true
	}
	t.Cleanup(func() { probeStartedHook = nil })
	runProbe(t, config, fixture.URL+"/generate_204", &countingProtector{})
	if !sampled {
		t.Fatal("probe box never reached the started state")
	}
	for _, s := range during {
		t.Errorf("probe box opened a listener: %s %s", s.proto, s.local)
	}
	for _, s := range newListeners(t, before) {
		t.Errorf("listener left after probe: %s %s", s.proto, s.local)
	}
}
