#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac -d "$out" \
  "$repo_root/src/main/java/dev/oreslang/runtime/TrustedChildProcessRunner.java" \
  "$repo_root/tests/host/TrustedChildProcessRunnerSmoke.java"
java -cp "$out" dev.oreslang.runtime.TrustedChildProcessRunnerSmoke
