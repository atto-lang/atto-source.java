#!/usr/bin/env bash
set -euo pipefail

# Standalone host-only reference-model check: no Maven, secrets or checkout
# beyond the current local repository. Does not prove guest SoA lowering.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
javac -encoding UTF-8 -d "$tmp_dir" \
  "$repo_root/src/main/java/dev/oreslang/dop/SoaSnapshot.java" \
  "$repo_root/src/test/java/dev/oreslang/dop/SoaSnapshotSmoke.java"
java -ea -cp "$tmp_dir" dev.oreslang.dop.SoaSnapshotSmoke
