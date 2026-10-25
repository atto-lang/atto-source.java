#!/usr/bin/env bash
# Dependency-free effect-closure model gate; JDK 21+, no Maven or GitHub token.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" \
  "$root/src/main/java/dev/oreslang/types/EffectContracts.java" \
  "$root/tools/formal-standalone/VerifyEffectClosure.java"
java -cp "$tmp" VerifyEffectClosure
