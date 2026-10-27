package bridge

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"io"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common/varbin"
)

// Layout of sing-box common/srs and sing common/domain; TestGeoSiteEncodingMatchesCore pins it to the bundled core.
const (
	srsRuleDefault       = 0
	srsItemDomain        = 2
	srsItemDomainKeyword = 3
	srsItemDomainRegex   = 4
	srsItemFinal         = 0xFF

	matcherRootLabel   = '\n'
	matcherPrefixLabel = '\r'
)

// geoSiteSet is one domain rule; keys are the core matcher's reversed domains, suffixes ending in a label sentinel.
type geoSiteSet struct {
	keys     []string
	keywords []string
	regexes  []string
}

func (s *geoSiteSet) addDomain(domain []byte) {
	s.keys = append(s.keys, matcherKey(domain, 0))
}

func (s *geoSiteSet) addSuffix(suffix []byte) {
	if suffix[0] == '.' {
		s.keys = append(s.keys, matcherKey(suffix, matcherPrefixLabel))
	} else {
		s.keys = append(s.keys, matcherKey(suffix, matcherRootLabel))
	}
}

func matcherKey(domain []byte, sentinel byte) string {
	var b strings.Builder
	b.Grow(len(domain) + 1)
	for rest := domain; len(rest) > 0; {
		_, size := utf8.DecodeLastRune(rest)
		b.Write(rest[len(rest)-size:])
		rest = rest[:len(rest)-size]
	}
	if sentinel != 0 {
		b.WriteByte(sentinel)
	}
	return b.String()
}

// writeGeoSiteSet encodes what srs.Write would, at zlib's fastest level (a tenth of the time for 5% more bytes); the core's trie builder queues every node at once, over a gigabyte for a million domains.
func writeGeoSiteSet(w io.Writer, s *geoSiteSet) error {
	if _, err := w.Write(append(srs.MagicBytes[:], C.RuleSetVersionCurrent)); err != nil {
		return err
	}
	compressed, err := zlib.NewWriterLevel(w, zlib.BestSpeed)
	if err != nil {
		return err
	}
	b := bufio.NewWriter(compressed)
	if len(s.keys) == 0 && len(s.keywords) == 0 && len(s.regexes) == 0 {
		_, _ = varbin.WriteUvarint(b, 0)
	} else {
		_, _ = varbin.WriteUvarint(b, 1)
		_ = b.WriteByte(srsRuleDefault)
		if len(s.keys) > 0 {
			_ = b.WriteByte(srsItemDomain)
			writeSuccinctSet(b, matcherKeys(s.keys))
		}
		writeSRSStrings(b, srsItemDomainKeyword, s.keywords)
		writeSRSStrings(b, srsItemDomainRegex, s.regexes)
		_ = b.WriteByte(srsItemFinal)
		_ = b.WriteByte(0)
	}
	// bufio.Writer keeps the first error and returns it here.
	if err := b.Flush(); err != nil {
		return err
	}
	return compressed.Close()
}

// matcherKeys sorts and dedupes in place and drops a full domain its own suffix already covers, as domain.NewMatcher does.
func matcherKeys(keys []string) []string {
	slices.Sort(keys)
	keys = slices.Compact(keys)
	out := keys[:0]
	for i, key := range keys {
		if i+1 < len(keys) && len(keys[i+1]) == len(key)+1 && strings.HasPrefix(keys[i+1], key) {
			if last := keys[i+1][len(key)]; last == matcherRootLabel || last == matcherPrefixLabel {
				continue
			}
		}
		out = append(out, key)
	}
	return out
}

// writeSuccinctSet encodes sorted keys as the core's succinct trie, visiting one level at a time so only two levels are held in memory.
func writeSuccinctSet(w *bufio.Writer, keys []string) {
	type span struct{ from, to int32 }
	var leaves, labelBitmap []uint64
	var labels []byte
	node, bit := 0, 0
	level, next := []span{{0, int32(len(keys))}}, []span(nil)
	for col := 0; len(level) > 0; col++ {
		next = next[:0]
		for _, sp := range level {
			from, to := int(sp.from), int(sp.to)
			if col == len(keys[from]) {
				from++
				setTrieBit(&leaves, node, 1)
			}
			for j := from; j < to; {
				start, label := j, keys[j][col]
				for j < to && keys[j][col] == label {
					j++
				}
				next = append(next, span{int32(start), int32(j)})
				labels = append(labels, label)
				setTrieBit(&labelBitmap, bit, 0)
				bit++
			}
			setTrieBit(&labelBitmap, bit, 1)
			bit++
			node++
		}
		level, next = next, level
	}
	_ = w.WriteByte(0)
	writeUint64s(w, leaves)
	writeUint64s(w, labelBitmap)
	_, _ = varbin.WriteUvarint(w, uint64(len(labels)))
	_, _ = w.Write(labels)
}

// setTrieBit grows the bitmap even for a zero bit, which fixes the encoded word count.
func setTrieBit(bitmap *[]uint64, i int, v uint64) {
	for i>>6 >= len(*bitmap) {
		*bitmap = append(*bitmap, 0)
	}
	(*bitmap)[i>>6] |= v << uint(i&63)
}

func writeUint64s(w *bufio.Writer, values []uint64) {
	_, _ = varbin.WriteUvarint(w, uint64(len(values)))
	var word [8]byte
	for _, v := range values {
		binary.BigEndian.PutUint64(word[:], v)
		_, _ = w.Write(word[:])
	}
}

func writeSRSStrings(w *bufio.Writer, item byte, values []string) {
	if len(values) == 0 {
		return
	}
	_ = w.WriteByte(item)
	_, _ = varbin.WriteUvarint(w, uint64(len(values)))
	for _, v := range values {
		_, _ = varbin.WriteUvarint(w, uint64(len(v)))
		_, _ = w.WriteString(v)
	}
}

// lowerDomain returns ASCII-lowercase input as is and copies anything else lowercased.
func lowerDomain(b []byte) []byte {
	for _, c := range b {
		if c >= utf8.RuneSelf || 'A' <= c && c <= 'Z' {
			return bytes.ToLower(b)
		}
	}
	return b
}
