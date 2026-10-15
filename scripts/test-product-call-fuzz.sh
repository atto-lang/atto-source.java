#!/usr/bin/env bash
# Host-side mutation fuzzing of the *real Java binder*, not coverage-guided
# guest fuzzing. Fails closed if the Oreslang test CLI is unavailable.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shim="${ORESLANG_TEST_SHIM:-oreslang}"
if ! command -v "$shim" >/dev/null 2>&1; then
  echo "oreslang test shim is not installed (set ORESLANG_TEST_SHIM); no fuzz test ran" >&2
  exit 2
fi
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  echo "JDK 21+ compiler and runtime required; no fuzz test ran" >&2
  exit 2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/project"
javac --release 21 -d "$tmp/classes" \
  "$root/src/main/java/dev/oreslang/types/FixedProductCallPlan.java" \
  "$root/src/test/java/dev/oreslang/FixedProductCallPlanFuzzTarget.java"
printf '[fuzz]\ncommand = ["java", "-cp", "%s", "dev.oreslang.FixedProductCallPlanFuzzTarget"]\nmax_bytes = 128\n' \
  "$tmp/classes" > "$tmp/project/oreslang-test.toml"

# Seed and input budget are stable, so a failure is reproducible. The
# temporary project includes only compiled classes plus the test manifest.
"$shim" test --root "$tmp/project" --fuzz \
  --seed "${ORESLANG_FUZZ_SEED:-1011}" --cases "${ORESLANG_FUZZ_CASES:-256}" \
  --timeout "${ORESLANG_FUZZ_TIMEOUT:-5}"
