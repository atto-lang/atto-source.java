#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# Exact checked-out OresSymbol and reference models; no host runtime or network dependency.
javac --release 21 -encoding UTF-8 -d "$workdir" \
  src/main/java/dev/oreslang/runtime/OresSymbol.java \
  tools/formal/SymbolicBehaviorModelCheck.java
java -cp "$workdir" SymbolicBehaviorModelCheck
