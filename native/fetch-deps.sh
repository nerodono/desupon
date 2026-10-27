#!/usr/bin/env bash
# Fetches the pinned upstream sources into native/third_party (kept pristine; build-core.sh patches a copy).
set -euo pipefail
cd "$(dirname "$0")"

# name|url|tag|commit
DEPS=(
  "sing-box-extended|https://github.com/shtorm-7/sing-box-extended.git|v1.14.3-extended-2.7.3|d9442d14b19ddd02605bf3ee893a171947038e19"
  "free-turn-proxy|https://github.com/hackdiaz-dev/free-turn-proxy.git|v4.1.0|465486ba96c4c2ff93443682ec900510b2e0ff9e"
)

git_q() { git -c advice.detachedHead=false "$@"; }

fetch() {
  local name=$1 url=$2 tag=$3 commit=$4 dir=third_party/$1
  if [ -d "$dir/.git" ]; then
    if [ -n "$(git -C "$dir" status --porcelain --untracked-files=no)" ]; then
      echo "fetch-deps: $dir has local modifications; refusing to touch it" >&2
      exit 1
    fi
    if [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ]; then
      echo "fetch-deps: $name $tag ok"
      return
    fi
    git_q -C "$dir" fetch --quiet --depth 1 origin "refs/tags/$tag:refs/tags/$tag"
  else
    rm -rf "$dir"
    mkdir -p third_party
    git_q clone --quiet --depth 1 --branch "$tag" "$url" "$dir"
  fi
  local got
  got=$(git -C "$dir" rev-parse "refs/tags/$tag^{commit}")
  if [ "$got" != "$commit" ]; then
    echo "fetch-deps: $name tag $tag resolves to $got, pinned $commit; upstream moved the tag" >&2
    exit 1
  fi
  git_q -C "$dir" checkout --quiet --detach "$commit"
  echo "fetch-deps: $name $tag -> $commit"
}

for dep in "${DEPS[@]}"; do
  IFS='|' read -r name url tag commit <<<"$dep"
  fetch "$name" "$url" "$tag" "$commit"
done
