#!/usr/bin/env bash
# JDK 21-compatible source-only host integration check; not GraalVM native image.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="$(mktemp -d)"
trap 'rm -rf "$dir"' EXIT
javac -d "$dir" \
  "$root/src/main/java/dev/oreslang/runtime/AtomicSharedCellV1.java" \
  "$root/src/test/java/dev/oreslang/runtime/AtomicSharedCellV1HostProbe.java"
java -cp "$dir" dev.oreslang.runtime.AtomicSharedCellV1HostProbe
