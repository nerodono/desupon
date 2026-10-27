package bridge

import (
	"bytes"
	"compress/zlib"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"google.golang.org/protobuf/encoding/protowire"
)

func pbBytes(b []byte, num protowire.Number, v []byte) []byte {
	b = protowire.AppendTag(b, num, protowire.BytesType)
	return protowire.AppendBytes(b, v)
}

func pbVarint(b []byte, num protowire.Number, v uint64) []byte {
	b = protowire.AppendTag(b, num, protowire.VarintType)
	return protowire.AppendVarint(b, v)
}

func cidrItem(ip []byte, bits uint64) []byte {
	return pbVarint(pbBytes(nil, 1, ip), 2, bits)
}

func prefixItem(prefix string) []byte {
	p := netip.MustParsePrefix(prefix)
	return cidrItem(p.Addr().AsSlice(), uint64(p.Bits()))
}

func domainItem(typ uint64, value string) []byte {
	var b []byte
	if typ != geoDomainPlain {
		b = pbVarint(b, 1, typ)
	}
	return pbBytes(b, 2, []byte(value))
}

func geoEntryMsg(code string, items ...[]byte) []byte {
	b := pbBytes(nil, 1, []byte(code))
	for _, item := range items {
		b = pbBytes(b, 2, item)
	}
	return b
}

func datList(entries ...[]byte) []byte {
	var b []byte
	for _, e := range entries {
		b = pbBytes(b, 1, e)
	}
	return b
}

func writeDat(t *testing.T, content []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "test.dat")
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func testGeoIPDat(t *testing.T) string {
	ru := geoEntryMsg("RU",
		prefixItem("1.2.3.0/24"),
		prefixItem("::ffff:5.6.0.0/112"),
		prefixItem("2001:db8::/32"),
		cidrItem([]byte{10, 1, 2, 3}, 8),
	)
	// v2ray v5 fields: resource_hash, code, file_path.
	ru = pbBytes(ru, 4, []byte{1, 2, 3})
	ru = pbBytes(ru, 5, []byte("RU"))
	ru = pbBytes(ru, 68000, []byte("geoip.dat"))
	return writeDat(t, datList(
		ru,
		pbVarint(geoEntryMsg("NOT-RU", prefixItem("1.2.3.0/24")), 3, 1),
		geoEntryMsg("EMPTY"),
		pbVarint(geoEntryMsg("ALL"), 3, 1),
	))
}

func testGeoSiteDat(t *testing.T) string {
	mixed := geoEntryMsg("CATEGORY-RU",
		domainItem(geoDomainFull, "Full.Example.com"),
		domainItem(geoDomainRoot, "example.org"),
		domainItem(geoDomainRoot, "пример.рф"),
		domainItem(geoDomainPlain, "keyword"),
		domainItem(geoDomainRegex, `^re[0-9]+\.test$`),
		domainItem(geoDomainRegex, `^(?!skip)lookahead\.test$`),
		domainItem(geoDomainRegex, ""),
		domainItem(geoDomainFull, "example.org"),
		// Domain.attribute {key: "ads", bool_value: true}
		pbBytes(domainItem(geoDomainRoot, "attr.example"), 3, pbVarint(pbBytes(nil, 1, []byte("ads")), 2, 1)),
		domainItem(7, "future.example"),
		domainItem(geoDomainFull, "bad\nhost.example"),
	)
	mixed = pbBytes(mixed, 3, []byte{0xaa, 0xbb})
	mixed = pbBytes(mixed, 68000, []byte("geosite.dat"))
	codeLast := pbBytes(pbBytes(nil, 2, domainItem(geoDomainRoot, "blocked.example")), 1, []byte("ru blocked"))
	return writeDat(t, append(pbVarint(nil, 2, 42), datList(
		mixed,
		geoEntryMsg("GEOLOCATION-!CN", domainItem(geoDomainRoot, "google.com")),
		codeLast,
		geoEntryMsg("RU-BLOCKED", domainItem(geoDomainRoot, "rutracker.org")),
		geoEntryMsg("ru-blocked-2"),
		geoEntryMsg("A/B Ü"),
		geoEntryMsg("ALL-SKIPPED", domainItem(geoDomainRegex, `(?=x)`)),
	)...))
}

func geoIndexOf(t *testing.T, path, kind string) []geoCategory {
	t.Helper()
	out, err := GeoDataIndex(path, kind)
	if err != nil {
		t.Fatalf("GeoDataIndex: %v", err)
	}
	var index []geoCategory
	if err := json.Unmarshal([]byte(out), &index); err != nil {
		t.Fatal(err)
	}
	return index
}

func geoConvertAll(t *testing.T, path, kind, dir string, names ...string) map[string]geoConverted {
	t.Helper()
	namesJSON, _ := json.Marshal(names)
	out, err := GeoDataConvert(path, kind, string(namesJSON), dir)
	if err != nil {
		t.Fatalf("GeoDataConvert: %v", err)
	}
	var manifest []geoConverted
	if err := json.Unmarshal([]byte(out), &manifest); err != nil {
		t.Fatal(err)
	}
	byName := map[string]geoConverted{}
	for _, m := range manifest {
		byName[m.Name] = m
	}
	return byName
}

func loadTestRuleSet(t *testing.T, path string) adapter.RuleSet {
	t.Helper()
	set, err := R.NewLocalRuleSet(context.Background(), log.NewNOPFactory().Logger(), "test", option.RuleSet{
		Type:         C.RuleSetTypeLocal,
		Format:       C.RuleSetFormatBinary,
		LocalOptions: option.LocalRuleSet{Path: path},
	})
	if err != nil {
		t.Fatalf("load %s: %v", filepath.Base(path), err)
	}
	t.Cleanup(func() { _ = set.Close() })
	return set
}

func matchHost(set adapter.RuleSet, host string) bool {
	metadata := &adapter.InboundContext{}
	if addr, err := netip.ParseAddr(host); err == nil {
		metadata.Destination = M.SocksaddrFrom(addr, 443)
	} else {
		metadata.Domain = host
	}
	return set.Match(metadata)
}

func assertMatches(t *testing.T, name string, set adapter.RuleSet, want map[string]bool) {
	t.Helper()
	for host, expected := range want {
		if got := matchHost(set, host); got != expected {
			t.Errorf("%s matches %s = %v, want %v", name, host, got, expected)
		}
	}
}

func ruleCount(t *testing.T, path string) int {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	compat, err := srs.Read(file, false)
	if err != nil {
		t.Fatal(err)
	}
	return len(compat.Options.Rules)
}

func assertNoTemps(t *testing.T, dir string) {
	t.Helper()
	temps, _ := filepath.Glob(filepath.Join(dir, "*.tmp"))
	if len(temps) > 0 {
		t.Fatalf("temporary files left: %v", temps)
	}
}

func TestGeoDataIndex(t *testing.T) {
	site := geoIndexOf(t, testGeoSiteDat(t), geoKindSite)
	wantSite := []geoCategory{
		{"CATEGORY-RU", "geosite-category-ru", 11},
		{"GEOLOCATION-!CN", "geosite-geolocation-not-cn", 1},
		{"ru blocked", "geosite-ru-blocked", 1},
		{"RU-BLOCKED", "geosite-ru-blocked-2", 1},
		{"ru-blocked-2", "geosite-ru-blocked-2-2", 0},
		{"A/B Ü", "geosite-a-b--", 0},
		{"ALL-SKIPPED", "geosite-all-skipped", 1},
	}
	if fmt.Sprint(site) != fmt.Sprint(wantSite) {
		t.Fatalf("geosite index\n got %v\nwant %v", site, wantSite)
	}
	tag := regexp.MustCompile(`^[A-Za-z0-9._-]+$`)
	for _, c := range site {
		if !tag.MatchString(c.Name) {
			t.Errorf("name %q is not a valid rule-set tag", c.Name)
		}
	}

	ip := geoIndexOf(t, testGeoIPDat(t), geoKindIP)
	wantIP := []geoCategory{
		{"RU", "geoip-ru", 4},
		{"NOT-RU", "geoip-not-ru", 1},
		{"EMPTY", "geoip-empty", 0},
		{"ALL", "geoip-all", 0},
	}
	if fmt.Sprint(ip) != fmt.Sprint(wantIP) {
		t.Fatalf("geoip index\n got %v\nwant %v", ip, wantIP)
	}
}

func TestGeoDataConvertGeoIP(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "sets")
	manifest := geoConvertAll(t, testGeoIPDat(t), geoKindIP, dir, "geoip-ru", "geoip-not-ru", "geoip-empty", "geoip-all", "geoip-ru")
	want := map[string]geoConverted{
		"geoip-ru":     {"geoip-ru", 4, 0},
		"geoip-not-ru": {"geoip-not-ru", 1, 0},
		"geoip-empty":  {"geoip-empty", 0, 0},
		"geoip-all":    {"geoip-all", 0, 0},
	}
	if fmt.Sprint(manifest) != fmt.Sprint(want) {
		t.Fatalf("manifest\n got %v\nwant %v", manifest, want)
	}
	assertNoTemps(t, dir)

	set := func(name string) adapter.RuleSet { return loadTestRuleSet(t, filepath.Join(dir, name+".srs")) }
	assertMatches(t, "geoip-ru", set("geoip-ru"), map[string]bool{
		"1.2.3.4":     true,
		"1.2.4.1":     false,
		"5.6.7.8":     true,
		"5.7.0.1":     false,
		"10.200.0.1":  true,
		"2001:db8::1": true,
		"2001:db9::1": false,
	})
	assertMatches(t, "geoip-not-ru", set("geoip-not-ru"), map[string]bool{
		"1.2.3.4":     false,
		"8.8.8.8":     true,
		"2001:db8::1": true,
	})
	assertMatches(t, "geoip-empty", set("geoip-empty"), map[string]bool{"1.2.3.4": false, "2001:db8::1": false})
	assertMatches(t, "geoip-all", set("geoip-all"), map[string]bool{"1.2.3.4": true, "2001:db8::1": true})
	if n := ruleCount(t, filepath.Join(dir, "geoip-empty.srs")); n != 0 {
		t.Fatalf("empty category has %d rules", n)
	}
}

func TestGeoDataConvertGeoSite(t *testing.T) {
	dir := t.TempDir()
	dat := testGeoSiteDat(t)
	manifest := geoConvertAll(t, dat, geoKindSite, dir,
		"geosite-category-ru", "geosite-geolocation-not-cn", "geosite-ru-blocked", "geosite-ru-blocked-2", "geosite-ru-blocked-2-2", "geosite-all-skipped")
	want := map[string]geoConverted{
		"geosite-category-ru":        {"geosite-category-ru", 7, 4},
		"geosite-geolocation-not-cn": {"geosite-geolocation-not-cn", 1, 0},
		"geosite-ru-blocked":         {"geosite-ru-blocked", 1, 0},
		"geosite-ru-blocked-2":       {"geosite-ru-blocked-2", 1, 0},
		"geosite-ru-blocked-2-2":     {"geosite-ru-blocked-2-2", 0, 0},
		"geosite-all-skipped":        {"geosite-all-skipped", 0, 1},
	}
	if fmt.Sprint(manifest) != fmt.Sprint(want) {
		t.Fatalf("manifest\n got %v\nwant %v", manifest, want)
	}
	assertNoTemps(t, dir)

	set := func(name string) adapter.RuleSet { return loadTestRuleSet(t, filepath.Join(dir, name+".srs")) }
	assertMatches(t, "geosite-category-ru", set("geosite-category-ru"), map[string]bool{
		"full.example.com":     true,
		"FULL.example.COM":     true,
		"a.full.example.com":   false,
		"example.org":          true,
		"www.example.org":      true,
		"badexample.org":       false,
		"пример.рф":            true,
		"www.пример.рф":        true,
		"my-keyword-site.net":  true,
		"re42.test":            true,
		"rex.test":             false,
		"lookahead.test":       false,
		"x.attr.example":       true,
		"future.example":       false,
		"host.example":         false,
		"unrelated.example.ru": false,
	})
	assertMatches(t, "geosite-ru-blocked", set("geosite-ru-blocked"), map[string]bool{"blocked.example": true, "rutracker.org": false})
	assertMatches(t, "geosite-ru-blocked-2", set("geosite-ru-blocked-2"), map[string]bool{"rutracker.org": true, "blocked.example": false})
	for _, name := range []string{"geosite-ru-blocked-2-2", "geosite-all-skipped"} {
		if n := ruleCount(t, filepath.Join(dir, name+".srs")); n != 0 {
			t.Fatalf("%s has %d rules", name, n)
		}
		assertMatches(t, name, set(name), map[string]bool{"example.org": false, "x": false})
	}

	// A route referencing the converted sets passes the core's own config check and start.
	config := fmt.Sprintf(`{
  "log": {"level": "error"},
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {
    "rule_set": [
      {"type": "local", "tag": "geosite-category-ru", "format": "binary", "path": %q},
      {"type": "local", "tag": "geosite-all-skipped", "format": "binary", "path": %q}
    ],
    "rules": [{"rule_set": ["geosite-category-ru", "geosite-all-skipped"], "action": "reject"}],
    "final": "direct"
  }
}`, filepath.Join(dir, "geosite-category-ru.srs"), filepath.Join(dir, "geosite-all-skipped.srs"))
	if err := libbox.CheckConfig(config); err != nil {
		t.Fatalf("CheckConfig: %v", err)
	}
	startBox(t, config)
}

// The running core picks up a converted set replaced by rename without a restart.
func TestGeoDataReloadsRunningBox(t *testing.T) {
	dir := t.TempDir()
	first := writeDat(t, datList(geoEntryMsg("RU", prefixItem("1.2.3.0/24"))))
	geoConvertAll(t, first, geoKindIP, dir, "geoip-ru")
	b := startBox(t, fmt.Sprintf(`{
  "log": {"level": "error"},
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {
    "rule_set": [{"type": "local", "tag": "geoip-ru", "format": "binary", "path": %q}],
    "rules": [{"rule_set": "geoip-ru", "action": "reject"}],
    "final": "direct"
  }
}`, filepath.Join(dir, "geoip-ru.srs")))
	set, ok := b.Router().RuleSet("geoip-ru")
	if !ok {
		t.Fatal("rule-set not registered")
	}
	if !matchHost(set, "1.2.3.4") || matchHost(set, "9.9.9.9") {
		t.Fatal("initial set does not match its content")
	}
	second := writeDat(t, datList(geoEntryMsg("RU", prefixItem("9.9.9.0/24"))))
	geoConvertAll(t, second, geoKindIP, dir, "geoip-ru")
	deadline := time.Now().Add(5 * time.Second)
	for !matchHost(set, "9.9.9.9") {
		if time.Now().After(deadline) {
			t.Fatal("running box did not reload the replaced set")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if matchHost(set, "1.2.3.4") {
		t.Fatal("reloaded set still matches the old content")
	}
}

func srsPayload(t *testing.T, file []byte) []byte {
	t.Helper()
	if !bytes.HasPrefix(file, append(srs.MagicBytes[:], C.RuleSetVersionCurrent)) {
		t.Fatalf("bad header % x", file[:min(4, len(file))])
	}
	r, err := zlib.NewReader(bytes.NewReader(file[4:]))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// The lean geosite encoder must produce exactly what srs.Write produces, so a core bump that changes the format fails here.
func TestGeoSiteEncodingMatchesCore(t *testing.T) {
	random := rand.New(rand.NewPCG(1, 2))
	labels := []string{"a", "ab", "abc", "b", "ru", "com", "xn--p1ai", "пример", "0", "a-b", "cdn"}
	host := func() string {
		parts := make([]string, 1+random.IntN(4))
		for i := range parts {
			parts[i] = labels[random.IntN(len(labels))]
		}
		return strings.Join(parts, ".")
	}
	var large option.DefaultHeadlessRule
	for range 5000 {
		switch random.IntN(5) {
		case 0, 1:
			large.Domain = append(large.Domain, host())
		case 2, 3:
			large.DomainSuffix = append(large.DomainSuffix, host())
		default:
			large.DomainSuffix = append(large.DomainSuffix, "."+host())
		}
	}
	// A full domain repeated as its own suffix is dropped by the core matcher.
	large.Domain = append(large.Domain, large.DomainSuffix[:50]...)
	large.DomainKeyword = []string{"ads", "track", "ads"}
	large.DomainRegex = []string{`^cdn[0-9]+\.`, `\.example$`}

	cases := map[string]option.DefaultHeadlessRule{
		"large":    large,
		"domain":   {Domain: []string{"a.b"}},
		"suffix":   {DomainSuffix: []string{"a.b", ".c.d"}},
		"keyword":  {DomainKeyword: []string{"x"}},
		"regex":    {DomainRegex: []string{"^x$"}},
		"no rules": {},
	}
	for name, rule := range cases {
		t.Run(name, func(t *testing.T) {
			var set geoSiteSet
			for _, v := range rule.Domain {
				set.add(geoDomainFull, []byte(v))
			}
			for _, v := range rule.DomainSuffix {
				set.add(geoDomainRoot, []byte(v))
			}
			for _, v := range rule.DomainKeyword {
				set.add(geoDomainPlain, []byte(v))
			}
			for _, v := range rule.DomainRegex {
				set.add(geoDomainRegex, []byte(v))
			}
			var ours, core bytes.Buffer
			if err := writeGeoSiteSet(&ours, &set); err != nil {
				t.Fatal(err)
			}
			var plain option.PlainRuleSet
			if rule.IsValid() {
				plain.Rules = []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: rule}}
			}
			if err := srs.Write(&core, plain, C.RuleSetVersionCurrent); err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(srsPayload(t, ours.Bytes()), srsPayload(t, core.Bytes())) {
				t.Fatal("encoding differs from srs.Write")
			}
		})
	}
}

func TestGeoDataRejectsMalformed(t *testing.T) {
	valid := datList(geoEntryMsg("RU", prefixItem("1.2.3.0/24")))
	overflow := append(bytes.Repeat([]byte{0xff}, 10), 0x01)
	cases := map[string]struct {
		content []byte
		kind    string
	}{
		"empty file":         {nil, geoKindIP},
		"no list entries":    {pbVarint(nil, 2, 1), geoKindIP},
		"truncated":          {valid[:len(valid)-1], geoKindIP},
		"entry past the end": {append(protowire.AppendTag(nil, 1, protowire.BytesType), 0x7f, 0x0a), geoKindIP},
		"varint overflow":    {append(protowire.AppendTag(nil, 1, protowire.BytesType), overflow...), geoKindIP},
		"group wire type":    {protowire.AppendTag(nil, 1, protowire.StartGroupType), geoKindIP},
		"field number zero":  {[]byte{0x02, 0x00}, geoKindIP},
		"entry without code": {datList(pbBytes(nil, 2, prefixItem("1.2.3.0/24"))), geoKindIP},
		"geoip read as site": {valid, geoKindSite},
		"geosite read as ip": {datList(geoEntryMsg("RU", domainItem(geoDomainRoot, "example.ru"))), geoKindIP},
		"plain domain as ip": {datList(geoEntryMsg("RU", domainItem(geoDomainPlain, "example"))), geoKindIP},
		"code of wrong type": {datList(pbVarint(nil, 1, 5)), geoKindIP},
		"reverse wrong type": {datList(pbBytes(geoEntryMsg("RU"), 3, []byte{1})), geoKindIP},
		"unknown kind":       {valid, "geodomain"},
		"bad address length": {datList(geoEntryMsg("RU", prefixItem("1.2.3.0/24"), cidrItem([]byte{1, 2, 3, 4, 5}, 8))), geoKindIP},
		"bad prefix length":  {datList(geoEntryMsg("RU", cidrItem([]byte{1, 2, 3, 4}, 33))), geoKindIP},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			path := writeDat(t, c.content)
			_, err := GeoDataIndex(path, c.kind)
			if err == nil {
				t.Fatal("index accepted it")
			}
			t.Logf("rejected: %v", err)
			if _, err := GeoDataConvert(path, c.kind, `["geoip-ru"]`, t.TempDir()); err == nil {
				t.Fatal("convert accepted it")
			}
		})
	}
	if _, err := GeoDataIndex(filepath.Join(t.TempDir(), "absent.dat"), geoKindIP); err == nil {
		t.Fatal("index of a missing file succeeded")
	}

	t.Run("no file replaced unless all convert", func(t *testing.T) {
		dir := t.TempDir()
		installed := filepath.Join(dir, "geoip-good.srs")
		if err := os.WriteFile(installed, []byte("previous"), 0o644); err != nil {
			t.Fatal(err)
		}
		path := writeDat(t, datList(
			geoEntryMsg("GOOD", prefixItem("1.2.3.0/24")),
			geoEntryMsg("BAD-LENGTH", cidrItem([]byte{1, 2, 3, 4, 5}, 8)),
			geoEntryMsg("BAD-PREFIX", cidrItem([]byte{1, 2, 3, 4}, 33)),
		))
		if _, err := GeoDataIndex(path, geoKindIP); err == nil {
			t.Fatal("index accepted a file that does not convert in full")
		}
		for _, bad := range []string{"geoip-bad-length", "geoip-bad-prefix"} {
			if _, err := GeoDataConvert(path, geoKindIP, `["geoip-good","`+bad+`"]`, dir); err == nil {
				t.Fatalf("%s converted", bad)
			}
		}
		_, err := GeoDataConvert(path, geoKindIP, `["geoip-good","geoip-absent","geoip-gone"]`, dir)
		if err == nil || !strings.Contains(err.Error(), "geoip-absent, geoip-gone") {
			t.Fatalf("missing names: %v", err)
		}
		if _, err := GeoDataConvert(path, geoKindIP, `{"geoip-good":true}`, dir); err == nil {
			t.Fatal("non-array names accepted")
		}
		if content, _ := os.ReadFile(installed); string(content) != "previous" {
			t.Fatal("installed set was replaced by a failed conversion")
		}
		assertNoTemps(t, dir)
	})
}

func TestGeoDataPanicIsAnError(t *testing.T) {
	out, err := geoCall(func() (string, error) { panic("index out of range") })
	if err == nil || out != "" || !strings.Contains(err.Error(), "index out of range") {
		t.Fatalf("got %q, %v", out, err)
	}
}
