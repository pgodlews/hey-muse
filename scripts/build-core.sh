#!/bin/bash
# Builds the Go core (core/heymuse) into app/libs/heymuse-core.aar with gomobile.
# Needs Go, ANDROID_HOME, and an NDK (ANDROID_NDK_HOME, or the one Gradle passes in).
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$repo_dir/app/libs/heymuse-core.aar}"

# The x/mobile version is the one core/go.mod requires, so the bind tool matches the bind package.
mobile_version="$(cd "$repo_dir/core" && go list -m -f '{{.Version}}' golang.org/x/mobile)"
gobin="$repo_dir/.tools/go/bin"
if [ ! -x "$gobin/gomobile" ] || [ ! -x "$gobin/gobind" ] || [ "$(cat "$gobin/.version" 2>/dev/null)" != "$mobile_version" ]; then
  mkdir -p "$gobin"
  GOBIN="$gobin" go install "golang.org/x/mobile/cmd/gomobile@$mobile_version" "golang.org/x/mobile/cmd/gobind@$mobile_version"
  echo "$mobile_version" > "$gobin/.version"
fi
export PATH="$gobin:$PATH"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi

mkdir -p "$(dirname "$out")"
cd "$repo_dir/core"
# All Portals are 64-bit ARM; minSdk matches the app's.
gomobile bind -target=android/arm64 -androidapi 28 -trimpath -ldflags="-s -w" -o "$out" ./heymuse
rm -f "${out%.aar}-sources.jar"
echo "$out"
