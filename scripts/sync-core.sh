#!/usr/bin/env bash
# Initialize or update the core/ submodule from https://github.com/JS8Call-improved/JS8Core
#
# Usage: scripts/sync-core.sh [path-to-js8core-source]
#
# What it does:
#   1. Updates the git submodule at core/ (or copies from an explicit override path).
#   2. Records the upstream revision in core/UPSTREAM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:-}"
DST="$ROOT/core"

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

echo "==> recording upstream revision"
{
  echo "repository: https://github.com/JS8Call-improved/JS8Core.git"
  echo "revision: $(git -C "$DST" rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "synced: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$DST/UPSTREAM"

echo "==> done: core/ synced"
echo "    next: scripts/test-core.sh"
echo "    then: cd android && ./gradlew :app:assembleRelease"
