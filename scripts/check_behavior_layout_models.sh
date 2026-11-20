#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# Real repository-owned Symbol implementation; no host engine dependency.
javac --release 21 -encoding UTF-8 -d "$scratch" \
  src/main/java/dev/oreslang/runtime/OresSymbol.java \
  tools/formal/BehaviorLayoutModel.java
java -cp "$scratch" BehaviorLayoutModel
