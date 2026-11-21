#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# No Maven, Gradle, network, simulator or JUnit dependency is required here.
# Host-side only: this is not a compiled AttoLang guest / Native Image receipt.
javac -Xlint:all -Werror -d "$tmp" \
  "$repo_root/src/test/java/dev/oreslang/runtime/CounterLiveTurnModel.java" \
  "$repo_root/verification/liveview-counter-host/CounterLiveTurnModelHarness.java" \
  "$repo_root/verification/liveview-counter-host/CounterLiveRetirementRaceHarness.java"
java -cp "$tmp" dev.oreslang.runtime.CounterLiveTurnModelHarness
java -cp "$tmp" dev.oreslang.runtime.CounterLiveRetirementRaceHarness
