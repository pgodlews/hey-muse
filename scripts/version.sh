#!/bin/bash
# Prints Android version settings for CI, derived from the newest vX.Y.Z tag reachable from HEAD:
#   tagged commit v0.2.0     -> HEYMUSE_VERSION_NAME=0.2.0             HEYMUSE_VERSION_CODE=20000
#   3 commits after v0.2.0   -> HEYMUSE_VERSION_NAME=0.2.0-3-gabc1234  HEYMUSE_VERSION_CODE=20003
#   next tag v0.2.1          -> HEYMUSE_VERSION_NAME=0.2.1             HEYMUSE_VERSION_CODE=20100
# Commits since the tag are capped at 99, so the next release always upgrades earlier main builds.
# Minor and patch must be <= 99. Needs full history and tags (actions/checkout with fetch-depth: 0).
set -euo pipefail
cd "$(dirname "$0")/.."

if desc=$(git describe --tags --long --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null) &&
   [[ $desc =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)-([0-9]+)-g([0-9a-f]+)$ ]]; then
  major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} ahead=${BASH_REMATCH[4]}
else
  major=0 minor=0 patch=0 ahead=$(git rev-list --count HEAD)
fi

if (( 10#$minor > 99 || 10#$patch > 99 )); then
  echo "version.sh: minor and patch must be <= 99 (got $major.$minor.$patch)" >&2
  exit 1
fi

name="$((10#$major)).$((10#$minor)).$((10#$patch))"
if (( ahead > 0 )); then
  name="$name-$ahead-g$(git rev-parse --short=7 HEAD)"
fi
code=$(( 10#$major * 1000000 + 10#$minor * 10000 + 10#$patch * 100 + (ahead > 99 ? 99 : ahead) ))
if (( code < 1 )); then
  code=1
fi

echo "HEYMUSE_VERSION_NAME=$name"
echo "HEYMUSE_VERSION_CODE=$code"
