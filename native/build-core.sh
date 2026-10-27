#!/usr/bin/env bash
# Builds the Go core AAR and publishes it to native/build/m2. Run inside `nix develop`.
# Usage: ./native/build-core.sh [gomobile targets, default android/arm64,android/arm]
set -euo pipefail
cd "$(dirname "$0")"
NATIVE=$PWD

VERSION=0.1.0
SBX_TAG=v1.14.3-extended-2.7.3
TARGETS=${1:-android/arm64,android/arm}
TAGS=with_gvisor,with_quic,with_wireguard,with_utls,badlinkname,tfogo_checklinkname0
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SBX_TAG#v} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384 -s -w -buildid="

step() { printf '\n== %s\n' "$*"; }

for tool in go gomobile gobind git tar; do
  command -v "$tool" >/dev/null || { echo "build-core: $tool not on PATH; run inside nix develop" >&2; exit 1; }
done
: "${ANDROID_NDK_HOME:?build-core: ANDROID_NDK_HOME unset; run inside nix develop}"

step "fetch pinned sources"
./fetch-deps.sh

# Patched copy is rebuilt whenever the pinned commit or a patch changes.
prepare() {
  local name=$1 src=third_party/$1 dst=build/src/$1 stamp
  stamp=$( { git -C "$src" rev-parse HEAD; cat patches/"$name"-*.patch 2>/dev/null; } | sha256sum | cut -d' ' -f1)
  if [ -f "$dst/.desupon-stamp" ] && [ "$(cat "$dst/.desupon-stamp")" = "$stamp" ]; then
    echo "build-core: $dst up to date"
    return
  fi
  rm -rf "$dst"
  mkdir -p "$dst"
  git -C "$src" archive --format=tar HEAD | tar -x -C "$dst"
  for p in "$NATIVE"/patches/"$name"-*.patch; do
    [ -e "$p" ] || continue
    # Inside the app repository git apply would silently skip paths outside the cwd; the ceiling keeps it out.
    (cd "$dst" && GIT_CEILING_DIRECTORIES="$NATIVE/build/src" git apply --whitespace=nowarn "$p")
    echo "build-core: applied $(basename "$p")"
  done
  echo "$stamp" >"$dst/.desupon-stamp"
}

# Patched copy of a Go dependency, taken from the module cache at the version sing-box-extended requires.
prepare_module() {
  local name=$1 path=$2 dst=build/src/$1 info dir version stamp
  info=$(cd build/src/sing-box-extended && GOWORK=off go mod download -json "$path")
  dir=$(printf '%s\n' "$info" | sed -n 's/^[[:space:]]*"Dir": "\(.*\)",$/\1/p')
  version=$(printf '%s\n' "$info" | sed -n 's/^[[:space:]]*"Version": "\(.*\)",$/\1/p')
  [ -d "$dir" ] || { echo "build-core: $path not in the module cache" >&2; exit 1; }
  stamp=$( { echo "$path@$version"; cat patches/"$name"-*.patch 2>/dev/null; } | sha256sum | cut -d' ' -f1)
  if [ -f "$dst/.desupon-stamp" ] && [ "$(cat "$dst/.desupon-stamp")" = "$stamp" ]; then
    echo "build-core: $dst up to date"
    return
  fi
  rm -rf "$dst"
  mkdir -p "$dst"
  cp -R "$dir"/. "$dst"
  chmod -R u+w "$dst"
  for p in "$NATIVE"/patches/"$name"-*.patch; do
    [ -e "$p" ] || continue
    (cd "$dst" && GIT_CEILING_DIRECTORIES="$NATIVE/build/src" git apply --whitespace=nowarn "$p")
    echo "build-core: applied $(basename "$p")"
  done
  echo "$stamp" >"$dst/.desupon-stamp"
}

step "prepare patched sources"
prepare sing-box-extended
prepare_module sing-quic github.com/sagernet/sing-quic

step "go vet"
go vet -tags "$TAGS" ./...

step "go test"
mkdir -p build
go test -count=1 -v -ldflags=-checklinkname=0 -tags "$TAGS" ./... 2>&1 | tee build/go-test.log
# interop/ is its own workspace, so the reference servers it runs never enter the AAR's build list.
(cd interop && go vet -tags "$TAGS" ./... && go test -count=1 -v -ldflags=-checklinkname=0 -tags "$TAGS" ./...) 2>&1 | tee -a build/go-test.log
for t in TestOwnSocketsDetectsLoopbackListeners TestProbeOpensNoListeners TestProbeStrict204 TestProbeWireGuardNode TestProbeConfigRejectsListenersAndSharedState TestSelectOutboundSwitchesSelector TestControlWithoutService TestMaskSecrets TestGoldenConfigs TestGoldenInvalidConfigsRejected TestRealityFragmentedHandshake TestProbeSkipsChainHops TestProbeStagedWarm TestProbeSecondRequestFailureKeepsFirst TestProbePerNodeTimeouts TestProbeConfigIPv4Only TestThroughputCapsAndCounts TestHysteria2ObfsInterop TestJuicityInterop TestJuicityUDPInterop TestGeoDataIndex TestGeoDataConvertGeoIP TestGeoDataConvertGeoSite TestGeoDataReloadsRunningBox TestGeoSiteEncodingMatchesCore TestGeoDataRejectsMalformed; do
  grep -q -- "--- PASS: $t " build/go-test.log || { echo "build-core: required test $t did not pass" >&2; exit 1; }
done
if grep -q -- "--- SKIP" build/go-test.log; then
  echo "build-core: tests were skipped" >&2
  exit 1
fi

step "gomobile bind ($TARGETS)"
rm -rf build/aar
mkdir -p build/aar
gomobile bind -target "$TARGETS" -androidapi 29 \
  -javapkg=moe.desupon.core -libname=dcore -trimpath \
  -tags "$TAGS" -ldflags "$LDFLAGS" \
  -o build/aar/core.aar \
  github.com/sagernet/sing-box/experimental/libbox github.com/desupon/core/bridge

step "16 KB page alignment"
READELF=$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -n1)
[ -x "$READELF" ] || { echo "build-core: llvm-readelf not found in NDK" >&2; exit 1; }
rm -rf build/aar/x
mkdir -p build/aar/x
(cd build/aar/x && jar xf ../core.aar jni)
bad=0
for so in build/aar/x/jni/*/libdcore.so; do
  abi=$(basename "$(dirname "$so")")
  aligns=$("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }')
  for a in $aligns; do
    if [ $((a)) -lt $((0x4000)) ]; then
      echo "build-core: $abi LOAD segment aligned to $a (< 0x4000)" >&2
      bad=1
    fi
  done
  echo "build-core: $abi LOAD alignments: $(echo $aligns)"
done
[ "$bad" = 0 ] || exit 1

step "publish moe.desupon:core:$VERSION"
REPO=build/m2/moe/desupon/core
rm -rf "$REPO"
mkdir -p "$REPO/$VERSION"
cp build/aar/core.aar "$REPO/$VERSION/core-$VERSION.aar"
[ -f build/aar/core-sources.jar ] && cp build/aar/core-sources.jar "$REPO/$VERSION/core-$VERSION-sources.jar"
cat >"$REPO/$VERSION/core-$VERSION.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>moe.desupon</groupId>
  <artifactId>core</artifactId>
  <version>$VERSION</version>
  <packaging>aar</packaging>
</project>
EOF
cat >"$REPO/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>moe.desupon</groupId>
  <artifactId>core</artifactId>
  <versioning>
    <latest>$VERSION</latest>
    <release>$VERSION</release>
    <versions><version>$VERSION</version></versions>
  </versioning>
</metadata>
EOF
(cd "$REPO/$VERSION" && sha256sum core-"$VERSION".aar)
echo "build-core: published $NATIVE/$REPO/$VERSION"
