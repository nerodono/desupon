package bridge

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"
	E "github.com/sagernet/sing/common/exceptions"
	"google.golang.org/protobuf/encoding/protowire"
)

const (
	geoKindIP   = "geoip"
	geoKindSite = "geosite"

	maxGeoCode = 1 << 10
	maxGeoItem = 1 << 20
)

// v2ray Domain.Type
const (
	geoDomainPlain = iota
	geoDomainRegex
	geoDomainRoot
	geoDomainFull
)

var errGeoMalformed = E.New("geodata: malformed file")

type geoCategory struct {
	Code  string `json:"code"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type geoConverted struct {
	Name    string `json:"name"`
	Count   int    `json:"count"`
	Skipped int    `json:"skipped"`
}

// GeoDataIndex lists the categories of a v2ray geoip.dat or geosite.dat (kind "geoip"|"geosite") as JSON [{code, name, count}]; name is the rule-set tag GeoDataConvert accepts.
func GeoDataIndex(datPath, kind string) (string, error) {
	return geoCall(func() (string, error) { return geoIndex(datPath, kind) })
}

// GeoDataConvert writes <outDir>/<name>.srs for every name of namesJSON and returns JSON [{name, count, skipped}]; no file is replaced unless all of them convert and load.
func GeoDataConvert(datPath, kind, namesJSON, outDir string) (string, error) {
	return geoCall(func() (string, error) {
		// The largest categories leave hundreds of megabytes of garbage in the process that runs the tunnel.
		defer debug.FreeOSMemory()
		return geoConvert(datPath, kind, namesJSON, outDir)
	})
}

// geoCall turns a panic on a downloaded file into an error, which would otherwise take the tunnel's process down.
func geoCall(f func() (string, error)) (string, error) {
	return onGoroutineErr(func() (out string, err error) {
		defer func() {
			if r := recover(); r != nil {
				out, err = "", fmt.Errorf("geodata: panic: %v", r)
			}
			err = maskErr(err)
		}()
		return f()
	})
}

func geoIndex(datPath, kind string) (string, error) {
	d, err := openGeoData(datPath, kind)
	if err != nil {
		return "", err
	}
	defer d.file.Close()
	out := make([]geoCategory, len(d.entries))
	for i, e := range d.entries {
		count := 0
		_, err := d.walk(e, func(item []byte) error {
			count++
			return d.checkItem(item)
		})
		if err != nil {
			return "", err
		}
		out[i] = geoCategory{Code: e.code, Name: e.name, Count: count}
	}
	content, err := json.Marshal(out)
	return string(content), err
}

func geoConvert(datPath, kind, namesJSON, outDir string) (string, error) {
	var names []string
	if err := json.Unmarshal([]byte(namesJSON), &names); err != nil {
		return "", E.Cause(err, "geodata: parse names")
	}
	d, err := openGeoData(datPath, kind)
	if err != nil {
		return "", err
	}
	defer d.file.Close()
	byName := make(map[string]geoEntry, len(d.entries))
	for _, e := range d.entries {
		byName[e.name] = e
	}
	var wanted, missing []string
	seen := map[string]bool{}
	for _, name := range names {
		if seen[name] {
			continue
		}
		seen[name] = true
		if _, ok := byName[name]; ok {
			wanted = append(wanted, name)
		} else {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return "", E.New("geodata: categories not found: ", strings.Join(missing, ", "))
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return "", E.Cause(err, "geodata: create output directory")
	}
	var temps []string
	defer func() {
		for _, path := range temps {
			_ = os.Remove(path)
		}
	}()
	out := make([]geoConverted, 0, len(wanted))
	for _, name := range wanted {
		temp := filepath.Join(outDir, name+".srs.tmp")
		temps = append(temps, temp)
		converted, err := d.convert(byName[name], temp)
		if err != nil {
			return "", E.Cause(err, "geodata: convert ", name)
		}
		if err := loadRuleSet(temp, name); err != nil {
			return "", E.Cause(err, "geodata: load ", name)
		}
		out = append(out, converted)
	}
	for i, name := range wanted {
		if err := os.Rename(temps[i], filepath.Join(outDir, name+".srs")); err != nil {
			return "", E.Cause(err, "geodata: install ", name)
		}
	}
	temps = nil
	content, err := json.Marshal(out)
	return string(content), err
}

func writeSynced(path string, write func(io.Writer) error) error {
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	buffered := bufio.NewWriterSize(file, 64<<10)
	err = write(buffered)
	if err == nil {
		err = buffered.Flush()
	}
	if err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	return err
}

// loadRuleSet builds the set the way the core does on start and on hot reload.
func loadRuleSet(path, tag string) error {
	set, err := R.NewLocalRuleSet(context.Background(), log.NewNOPFactory().Logger(), tag, option.RuleSet{
		Type:         C.RuleSetTypeLocal,
		Format:       C.RuleSetFormatBinary,
		LocalOptions: option.LocalRuleSet{Path: path},
	})
	if err != nil {
		return err
	}
	return set.Close()
}

type geoEntry struct {
	code, name string
	off, size  int64
}

type geoData struct {
	kind    string
	file    *os.File
	wire    *wireReader
	entries []geoEntry
}

func openGeoData(path, kind string) (*geoData, error) {
	if kind != geoKindIP && kind != geoKindSite {
		return nil, E.New("geodata: unknown kind: ", kind)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, E.Cause(err, "geodata: open")
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, E.Cause(err, "geodata: open")
	}
	d := &geoData{kind: kind, file: file, wire: newWireReader(file, info.Size())}
	if err := d.scan(); err != nil {
		_ = file.Close()
		return nil, err
	}
	return d, nil
}

// scan locates every category of the list (field 1) and reads its code; items are left on disk.
func (d *geoData) scan() error {
	w := d.wire
	used := map[string]bool{}
	for w.off < w.size {
		num, typ, err := w.tag(w.size)
		if err != nil {
			return err
		}
		if num != 1 || typ != protowire.BytesType {
			if err := w.skip(typ, w.size); err != nil {
				return err
			}
			continue
		}
		size, err := w.length(w.size)
		if err != nil {
			return err
		}
		e := geoEntry{off: w.off, size: size}
		e.code, err = d.code(e)
		if err != nil {
			return err
		}
		e.name = geoName(d.kind, e.code, used)
		d.entries = append(d.entries, e)
		w.seek(e.off + e.size)
	}
	if len(d.entries) == 0 {
		return E.New("geodata: no categories")
	}
	return nil
}

func (d *geoData) code(e geoEntry) (string, error) {
	w := d.wire
	end := e.off + e.size
	w.seek(e.off)
	for w.off < end {
		num, typ, err := w.tag(end)
		if err != nil {
			return "", err
		}
		if num != 1 {
			if err := w.skip(typ, end); err != nil {
				return "", err
			}
			continue
		}
		if typ != protowire.BytesType {
			return "", errGeoMalformed
		}
		code, err := w.payload(end, maxGeoCode)
		if err != nil {
			return "", err
		}
		if len(code) == 0 {
			break
		}
		return string(code), nil
	}
	return "", E.New("geodata: category without code at offset ", e.off)
}

// walk passes every CIDR or domain of the category to item and reports the geoip reverse_match flag.
func (d *geoData) walk(e geoEntry, item func([]byte) error) (bool, error) {
	w := d.wire
	end := e.off + e.size
	reverse := false
	w.seek(e.off)
	for w.off < end {
		num, typ, err := w.tag(end)
		if err != nil {
			return false, err
		}
		switch {
		case num == 2:
			if typ != protowire.BytesType {
				return false, errGeoMalformed
			}
			b, err := w.payload(end, maxGeoItem)
			if err != nil {
				return false, err
			}
			if err := item(b); err != nil {
				return false, err
			}
		case num == 3 && d.kind == geoKindIP:
			if typ != protowire.VarintType {
				return false, errGeoMalformed
			}
			v, err := w.varint(end)
			if err != nil {
				return false, err
			}
			reverse = v != 0
		default:
			if err := w.skip(typ, end); err != nil {
				return false, err
			}
		}
	}
	return reverse, nil
}

// checkItem fails on every item conversion fails on, so an indexed file converts in full; it also rejects a file of the other kind.
func (d *geoData) checkItem(item []byte) error {
	if d.kind == geoKindIP {
		_, err := parseGeoCIDR(item)
		return err
	}
	_, _, err := domainFields(item)
	return err
}

func (d *geoData) convert(e geoEntry, path string) (geoConverted, error) {
	if d.kind == geoKindIP {
		return d.convertIP(e, path)
	}
	return d.convertSite(e, path)
}

func (d *geoData) convertIP(e geoEntry, path string) (geoConverted, error) {
	converted := geoConverted{Name: e.name}
	var rule option.DefaultHeadlessRule
	var err error
	rule.Invert, err = d.walk(e, func(item []byte) error {
		prefix, err := parseGeoCIDR(item)
		if err != nil {
			return err
		}
		rule.IPCIDR = append(rule.IPCIDR, prefix.String())
		converted.Count++
		return nil
	})
	if err != nil {
		return converted, err
	}
	// A rule without conditions fails to load, so an empty category has no rules at all.
	var plain option.PlainRuleSet
	switch {
	case len(rule.IPCIDR) > 0:
		plain.Rules = []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: rule}}
	case rule.Invert:
		// v2ray's reverse match of nothing matches every address.
		plain.Rules = []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultHeadlessRule{IPCIDR: []string{"0.0.0.0/0", "::/0"}}}}
	}
	return converted, writeSynced(path, func(w io.Writer) error {
		return srs.Write(w, plain, C.RuleSetVersionCurrent)
	})
}

func (d *geoData) convertSite(e geoEntry, path string) (geoConverted, error) {
	converted := geoConverted{Name: e.name}
	var set geoSiteSet
	_, err := d.walk(e, func(item []byte) error {
		typ, value, err := domainFields(item)
		if err != nil {
			return err
		}
		if set.add(typ, value) {
			converted.Count++
		} else {
			converted.Skipped++
		}
		return nil
	})
	if err != nil {
		return converted, err
	}
	return converted, writeSynced(path, func(w io.Writer) error {
		return writeGeoSiteSet(w, &set)
	})
}

// add maps a v2ray domain onto the rule and reports whether it was kept; attributes are ignored.
func (s *geoSiteSet) add(typ uint64, raw []byte) bool {
	if len(raw) == 0 || !utf8.Valid(raw) {
		return false
	}
	if typ == geoDomainRegex {
		value := string(raw)
		if _, err := regexp.Compile(value); err != nil {
			return false
		}
		s.regexes = append(s.regexes, value)
		return true
	}
	// Control characters collide with the label sentinels of the core's domain matcher.
	if bytes.ContainsFunc(raw, func(r rune) bool { return r <= ' ' || r == 0x7f }) {
		return false
	}
	raw = lowerDomain(raw)
	switch typ {
	case geoDomainPlain:
		s.keywords = append(s.keywords, string(raw))
	case geoDomainRoot:
		s.addSuffix(raw)
	case geoDomainFull:
		s.addDomain(raw)
	default:
		return false
	}
	return true
}

func parseGeoCIDR(item []byte) (netip.Prefix, error) {
	ip, bits, err := cidrFields(item)
	if err != nil {
		return netip.Prefix{}, err
	}
	addr, ok := netip.AddrFromSlice(ip)
	if !ok {
		return netip.Prefix{}, E.New("geodata: bad address length ", len(ip))
	}
	if addr.Is4In6() && bits >= 96 {
		addr = addr.Unmap()
		bits -= 96
	}
	if bits > uint64(addr.BitLen()) {
		return netip.Prefix{}, E.New("geodata: bad prefix length ", bits)
	}
	return netip.PrefixFrom(addr, int(bits)).Masked(), nil
}

// cidrFields decodes v2ray CIDR{1: ip bytes, 2: prefix uint32}.
func cidrFields(b []byte) (ip []byte, bits uint64, err error) {
	err = eachField(b, func(num protowire.Number, typ protowire.Type, v []byte, n uint64) error {
		switch num {
		case 1:
			if typ != protowire.BytesType {
				return errGeoMalformed
			}
			ip = v
		case 2:
			if typ != protowire.VarintType {
				return errGeoMalformed
			}
			bits = n
		}
		return nil
	})
	return
}

// domainFields decodes v2ray Domain{1: type enum, 2: value string, 3: attributes}.
func domainFields(b []byte) (typ uint64, value []byte, err error) {
	err = eachField(b, func(num protowire.Number, wt protowire.Type, v []byte, n uint64) error {
		switch num {
		case 1:
			if wt != protowire.VarintType {
				return errGeoMalformed
			}
			typ = n
		case 2:
			if wt != protowire.BytesType {
				return errGeoMalformed
			}
			value = v
		}
		return nil
	})
	return
}

func eachField(b []byte, fn func(num protowire.Number, typ protowire.Type, v []byte, n uint64) error) error {
	for len(b) > 0 {
		num, typ, l := protowire.ConsumeTag(b)
		if l < 0 {
			return errGeoMalformed
		}
		b = b[l:]
		var v []byte
		var n uint64
		switch typ {
		case protowire.VarintType:
			n, l = protowire.ConsumeVarint(b)
		case protowire.BytesType:
			v, l = protowire.ConsumeBytes(b)
		case protowire.Fixed32Type, protowire.Fixed64Type:
			l = protowire.ConsumeFieldValue(num, typ, b)
		default:
			return errGeoMalformed
		}
		if l < 0 {
			return errGeoMalformed
		}
		b = b[l:]
		if err := fn(num, typ, v, n); err != nil {
			return err
		}
	}
	return nil
}

// geoName maps a code to a unique rule-set tag of [A-Za-z0-9._-]: "GEOLOCATION-!CN" becomes "geosite-geolocation-not-cn".
func geoName(kind, code string, used map[string]bool) string {
	var b strings.Builder
	b.WriteString(kind)
	b.WriteByte('-')
	for _, r := range strings.ToLower(code) {
		switch {
		case r == '!':
			b.WriteString("not-")
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '.', r == '_', r == '-':
			b.WriteRune(r)
		default:
			b.WriteByte('-')
		}
	}
	base := b.String()
	name := base
	for i := 2; used[name]; i++ {
		name = base + "-" + strconv.Itoa(i)
	}
	used[name] = true
	return name
}

// wireReader streams protobuf fields from a file; skipping past the buffer re-seeks instead of reading the bytes.
type wireReader struct {
	src  io.ReaderAt
	size int64
	off  int64
	r    *bufio.Reader
	buf  []byte
}

func newWireReader(src io.ReaderAt, size int64) *wireReader {
	return &wireReader{src: src, size: size, r: bufio.NewReaderSize(io.NewSectionReader(src, 0, size), 64<<10)}
}

func (w *wireReader) seek(off int64) {
	if d := off - w.off; d >= 0 && d <= int64(w.r.Buffered()) {
		_, _ = w.r.Discard(int(d))
	} else {
		w.r.Reset(io.NewSectionReader(w.src, off, w.size-off))
	}
	w.off = off
}

func (w *wireReader) varint(end int64) (uint64, error) {
	var v uint64
	for shift := uint(0); shift < 64; shift += 7 {
		if w.off >= end {
			return 0, errGeoMalformed
		}
		c, err := w.r.ReadByte()
		if err != nil {
			return 0, errGeoMalformed
		}
		w.off++
		if shift == 63 && c > 1 {
			return 0, errGeoMalformed
		}
		v |= uint64(c&0x7f) << shift
		if c < 0x80 {
			return v, nil
		}
	}
	return 0, errGeoMalformed
}

func (w *wireReader) tag(end int64) (protowire.Number, protowire.Type, error) {
	v, err := w.varint(end)
	if err != nil {
		return 0, 0, err
	}
	num, typ := protowire.DecodeTag(v)
	if num < protowire.MinValidNumber || num > protowire.MaxValidNumber {
		return 0, 0, errGeoMalformed
	}
	return num, typ, nil
}

func (w *wireReader) length(end int64) (int64, error) {
	v, err := w.varint(end)
	if err != nil {
		return 0, err
	}
	if v > uint64(end-w.off) {
		return 0, errGeoMalformed
	}
	return int64(v), nil
}

// payload reads a length-delimited value into a buffer that the next call reuses.
func (w *wireReader) payload(end int64, limit int) ([]byte, error) {
	n, err := w.length(end)
	if err != nil {
		return nil, err
	}
	if n > int64(limit) {
		return nil, errGeoMalformed
	}
	if int64(cap(w.buf)) < n {
		w.buf = make([]byte, n)
	}
	b := w.buf[:n]
	if _, err := io.ReadFull(w.r, b); err != nil {
		return nil, errGeoMalformed
	}
	w.off += n
	return b, nil
}

func (w *wireReader) skip(typ protowire.Type, end int64) error {
	var n int64
	switch typ {
	case protowire.VarintType:
		_, err := w.varint(end)
		return err
	case protowire.Fixed32Type:
		n = 4
	case protowire.Fixed64Type:
		n = 8
	case protowire.BytesType:
		var err error
		if n, err = w.length(end); err != nil {
			return err
		}
	default:
		return errGeoMalformed
	}
	if n > end-w.off {
		return errGeoMalformed
	}
	w.seek(w.off + n)
	return nil
}
