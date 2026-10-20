#!/usr/bin/env bash
# Isolated actual-source Java host topology smoke; not Maven/GraalVM/JNI proof.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
command -v javac >/dev/null
command -v java >/dev/null
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/oreslang/runtime/CpuTopology.java" \
  "$root/src/main/java/dev/oreslang/runtime/ExecutionPlacement.java" \
  "$root/src/test/java/dev/oreslang/runtime/RegionLocalitySmoke.java"
java -ea -cp "$tmp" dev.oreslang.runtime.RegionLocalitySmoke
