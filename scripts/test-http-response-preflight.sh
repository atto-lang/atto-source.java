#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" src/main/java/dev/atto/runtime/HttpResponseDraft.java src/test/java/dev/atto/runtime/HttpResponsePreflightCheck.java
java -cp "$out" dev.atto.runtime.HttpResponsePreflightCheck
