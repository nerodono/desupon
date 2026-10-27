package bridge

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/experimental/libbox"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	sjson "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

// Written by :core:config GoldenTest; regenerate there with -PupdateGolden.
const goldenDir = "../../core/config/src/test/resources/golden"

type deprecationRecorder struct{ names []string }

func (r *deprecationRecorder) ReportDeprecated(note deprecated.Note) {
	r.names = append(r.names, note.Name)
}

func goldenFiles(t *testing.T, dir string) []string {
	t.Helper()
	files, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	if len(files) == 0 {
		t.Fatalf("no golden files in %s; run :core:config:test first", dir)
	}
	return files
}

func readGolden(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(content)
}

// withRuleSetFiles points each local rule-set at a generated .srs, as the device does with bundled files: geoip-* gets an IP rule, others a domain rule.
func withRuleSetFiles(t *testing.T, config string) string {
	t.Helper()
	var root map[string]any
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		t.Fatal(err)
	}
	route, _ := root["route"].(map[string]any)
	sets := objects(route["rule_set"])
	if len(sets) == 0 {
		return config
	}
	dir := t.TempDir()
	for _, set := range sets {
		tag, _ := set["tag"].(string)
		rule := `{"domain_suffix":["example.ru"]}`
		if strings.HasPrefix(tag, "geoip") {
			rule = `{"ip_cidr":["192.0.2.0/24"]}`
		}
		compat, err := sjson.UnmarshalExtended[option.PlainRuleSetCompat]([]byte(`{"version":3,"rules":[` + rule + `]}`))
		if err != nil {
			t.Fatal(err)
		}
		plain, err := compat.Upgrade()
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(dir, tag+".srs")
		file, err := os.Create(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := srs.Write(file, plain, C.RuleSetVersion3); err != nil {
			t.Fatal(err)
		}
		_ = file.Close()
		set["path"] = path
	}
	out, err := json.Marshal(root)
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

// deprecations builds the box once more with a recorder, since CheckConfig drops deprecation reports.
func deprecations(t *testing.T, config string) []string {
	t.Helper()
	recorder := &deprecationRecorder{}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	defer cancel()
	ctx = service.ContextWith[deprecated.Manager](ctx, recorder)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newProbePlatform(nil, "", 0))
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
	if err != nil {
		t.Fatalf("decode: %v", maskErr(err))
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatalf("box.New: %v", maskErr(err))
	}
	_ = instance.Close()
	return recorder.names
}

// startWithoutTun starts the config minus TUN and cache file: DNS and route rules are only compiled on start.
func startWithoutTun(config string) ([]string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		return nil, err
	}
	delete(root, "inbounds")
	delete(root, "experimental")
	stripped, err := json.Marshal(root)
	if err != nil {
		return nil, err
	}
	recorder := &deprecationRecorder{}
	ctx, cancel := context.WithCancel(include.Context(context.Background()))
	defer cancel()
	ctx = service.ContextWith[deprecated.Manager](ctx, recorder)
	ctx = service.ContextWith[adapter.PlatformInterface](ctx, newProbePlatform(nil, "", 0))
	options, err := sjson.UnmarshalExtendedContext[option.Options](ctx, stripped)
	if err != nil {
		return nil, maskErr(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		return nil, maskErr(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		return nil, maskErr(err)
	}
	return recorder.names, nil
}

// from-parser/ holds parser fixtures run through the generator; a failure there names the node tag to trace back.
func TestGoldenConfigs(t *testing.T) {
	paths := append(goldenFiles(t, goldenDir), goldenFiles(t, filepath.Join(goldenDir, "from-parser"))...)
	for _, path := range paths {
		name := filepath.Base(path)
		if filepath.Base(filepath.Dir(path)) == "from-parser" {
			name = "from-parser/" + name
		}
		t.Run(name, func(t *testing.T) {
			config := withRuleSetFiles(t, readGolden(t, path))
			if err := libbox.CheckConfig(config); err != nil {
				t.Fatalf("CheckConfig: %v\nper node:\n%s", maskErr(err), strings.Join(nodeFailures(t, config), "\n"))
			}
			if names := deprecations(t, config); len(names) > 0 {
				t.Fatalf("deprecated options: %v", names)
			}
			names, err := startWithoutTun(config)
			if err != nil {
				t.Fatalf("start: %v\nper node:\n%s", err, strings.Join(nodeFailures(t, config), "\n"))
			}
			if len(names) > 0 {
				t.Fatalf("deprecated options on start: %v", names)
			}
			if !strings.HasPrefix(filepath.Base(path), "probe") {
				return
			}
			prepared, err := probeConfig(config, "192.0.2.53", false)
			if err != nil {
				t.Fatalf("probe guard: %v", maskErr(err))
			}
			if err := libbox.CheckConfig(string(prepared)); err != nil {
				t.Fatalf("CheckConfig after probe guard: %v", maskErr(err))
			}
			prepared, err = probeConfig(config, "192.0.2.53", true)
			if err != nil {
				t.Fatalf("probe guard ipv4_only: %v", maskErr(err))
			}
			if err := libbox.CheckConfig(string(prepared)); err != nil {
				t.Fatalf("CheckConfig after ipv4_only probe guard: %v", maskErr(err))
			}
		})
	}
}

// Each invalid golden breaks one generator rule; the core refusing it, at check or start, shows the rule is needed.
func TestGoldenInvalidConfigsRejected(t *testing.T) {
	for _, path := range goldenFiles(t, filepath.Join(goldenDir, "invalid")) {
		t.Run(filepath.Base(path), func(t *testing.T) {
			config := readGolden(t, path)
			err := libbox.CheckConfig(config)
			if err == nil {
				_, err = startWithoutTun(config)
			}
			if err == nil {
				t.Fatal("the core accepted it")
			}
			t.Logf("rejected: %v", maskErr(err))
		})
	}
}

// nodeFailures checks and starts every node of config on its own, so a failing golden names the culprit tags.
func nodeFailures(t *testing.T, config string) []string {
	t.Helper()
	var root map[string]any
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, section := range []string{"outbounds", "endpoints"} {
		for _, node := range objects(root[section]) {
			if skippedProbeTypes[node["type"].(string)] {
				continue
			}
			single, _ := json.Marshal(map[string]any{
				"dns":   map[string]any{"servers": []any{map[string]any{"type": "local", "tag": "local"}}},
				section: []any{node},
				"route": map[string]any{"auto_detect_interface": true},
			})
			err := libbox.CheckConfig(string(single))
			if err == nil {
				_, err = startWithoutTun(string(single))
			}
			if err != nil {
				out = append(out, fmt.Sprintf("%v (%v): %v", node["tag"], node["type"], maskErr(err)))
			}
		}
	}
	return out
}
