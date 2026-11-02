#!/usr/bin/env bash
# Standalone Java 21 host-only critical-admission verification.
# Does not compile or run Oreslang guest source.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
classes="$(mktemp -d)"
trap 'rm -rf -- "$classes"' EXIT
javac --release 21 -Xlint:all -Werror -d "$classes" \
  "$root/src/main/java/dev/oreslang/runtime/CriticalSectionGate.java" \
  "$root/src/test/java/dev/oreslang/CriticalSectionGateStandaloneTest.java" \
  "$root/src/test/java/dev/oreslang/CriticalSectionGateExecutorStressTest.java"
java -ea -cp "$classes" dev.oreslang.CriticalSectionGateStandaloneTest
java -ea -cp "$classes" dev.oreslang.CriticalSectionGateExecutorStressTest
