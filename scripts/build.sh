#!/bin/bash
# Builds a debug APK into dist/hey-muse.apk. The Gradle build runs scripts/build-core.sh itself.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cd "$repo_dir"
./gradlew :app:assembleDebug
mkdir -p "$repo_dir/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$repo_dir/dist/hey-muse.apk"
echo "$repo_dir/dist/hey-muse.apk"
