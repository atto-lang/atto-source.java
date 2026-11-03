#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac -Xlint:all -Werror -d "$tmp" \
  "$repo_root/src/test/java/dev/oreslang/runtime/CounterLiveTurnModel.java" \
  "$repo_root/verification/liveview-counter-host/CounterLiveTurnModelHarness.java"
java -cp "$tmp" dev.oreslang.runtime.CounterLiveTurnModelHarness
