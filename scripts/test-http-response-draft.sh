#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$root"
javac --release 21 -d "$tmp" \
  src/main/java/dev/oreslang/runtime/HttpResponseDraft.java \
  src/test/java/dev/oreslang/runtime/HttpResponseDraftStandaloneCheck.java
java -cp "$tmp" dev.oreslang.runtime.HttpResponseDraftStandaloneCheck
