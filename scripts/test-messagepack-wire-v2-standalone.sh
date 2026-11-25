#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$root/src/main/java/dev/oreslang/runtime/MessagePackWireV2.java"
harness_file="$root/src/test/standalone/dev/oreslang/runtime/MessagePackWireV2Harness.java"
command -v javac >/dev/null || { echo 'javac is required' >&2; exit 1; }
command -v java >/dev/null || { echo 'java is required' >&2; exit 1; }
[[ -f "$source_file" && -f "$harness_file" ]] || { echo 'wire v2 sources missing' >&2; exit 1; }
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" "$source_file" "$harness_file"
java -cp "$out" dev.oreslang.runtime.MessagePackWireV2Harness
