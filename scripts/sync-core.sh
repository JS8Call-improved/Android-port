#!/usr/bin/env bash
# Initialize or update the core/ submodule from https://github.com/JS8Call-improved/JS8Core
# and apply the Android NDK integration patches.
#
# Usage: scripts/sync-core.sh [path-to-js8core-source]
#
# What it does:
#   1. Updates the git submodule at core/ (or copies from an explicit override path).
#   2. Verifies and applies scripts/core-android-ndk.patch.
#   3. Records the upstream revision and patch digest in core/UPSTREAM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-}"
DST="$ROOT/core"
PATCH="$ROOT/scripts/core-android-ndk.patch"

if [ -n "$SRC" ]; then
  if [ ! -f "$SRC/CMakeLists.txt" ] || [ ! -d "$SRC/include/js8core" ]; then
    echo "error: '$SRC' does not look like a js8core source tree" >&2
    exit 1
  fi
  echo "==> copying core/ from override source: $SRC"
  rm -rf "$DST"
  mkdir -p "$DST"
  rsync -a --exclude='.git' --exclude='build' --exclude='build-*' --exclude='build_*' "$SRC"/ "$DST"/
else
  echo "==> initializing and updating core/ submodule"
  git -C "$ROOT" submodule update --init --recursive
  rm -rf "$DST/build" "$DST/build-review"
fi

if [ ! -f "$PATCH" ]; then
  echo "error: missing $PATCH" >&2
  exit 1
fi

echo "==> verifying Android NDK patch applies"
if ! patch -p1 -d "$DST" --dry-run --forward < "$PATCH" >/dev/null; then
  echo "error: $PATCH does not apply cleanly to core/" >&2
  exit 1
fi

echo "==> applying Android NDK patches"
patch -p1 -d "$DST" --forward < "$PATCH" >/dev/null

echo "==> recording upstream revision"
digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}
{
  echo "repository: https://github.com/JS8Call-improved/JS8Core.git"
  echo "revision: $(git -C "$DST" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "synced: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "patch-sha256: $(digest "$PATCH")"
} > "$DST/UPSTREAM"

echo "==> done: core/ synced and patched"
echo "    next: scripts/test-core.sh"
echo "    then: cd android && ./gradlew :app:assembleRelease"
