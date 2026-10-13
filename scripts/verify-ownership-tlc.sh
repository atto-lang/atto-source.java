#!/usr/bin/env bash
# Fail-closed TLC gate: one safe model and two independent negative controls.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
jar="$scratch/tla2tools.jar"
sha256='936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88'
url='https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar'
curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 "$url" --output "$jar"
printf '%s  %s\n' "$sha256" "$jar" | sha256sum --check --status

model='OwnershipCarrierHandoff'
cp "$repo_root/docs/formal/$model.tla" "$scratch/"
cp "$repo_root/docs/formal/$model.cfg" "$scratch/"
cp "$repo_root/docs/formal/${model}Unsafe.cfg" "$scratch/"
cp "$repo_root/docs/formal/${model}ReplayUnsafe.cfg" "$scratch/"
cd "$scratch"

run_safe() {
  local log="$scratch/safe.log"
  if ! java -XX:+UseParallelGC -jar "$jar" -workers 1 -config "$model.cfg" "$model.tla" >"$log" 2>&1; then
    cat "$log" >&2
    echo 'Safe TLC model did not pass' >&2
    exit 1
  fi
  if ! grep -Fq 'Model checking completed. No error has been found.' "$log"; then
    cat "$log" >&2
    echo 'Safe TLC process returned without a success verdict' >&2
    exit 1
  fi
  echo 'Safe TLC invariant gate passed'
  grep 'states generated' "$log" | tail -1 || true
}

expect_mutation() {
  local cfg="$1" invariant="$2" log="$scratch/$1.log"
  if java -XX:+UseParallelGC -jar "$jar" -workers 1 -config "$cfg" "$model.tla" >"$log" 2>&1; then
    cat "$log" >&2
    echo "Mutation $cfg unexpectedly passed" >&2
    exit 1
  fi
  if ! grep -Fq "Invariant $invariant is violated" "$log"; then
    cat "$log" >&2
    echo "Mutation $cfg failed for the wrong reason (expected $invariant)" >&2
    exit 1
  fi
  echo "Expected counterexample: $cfg -> $invariant"
}

run_safe
expect_mutation "${model}Unsafe.cfg" 'LoanSafeAtHandoff'
expect_mutation "${model}ReplayUnsafe.cfg" 'NoSideEffectReplay'
echo 'All three TLC verdicts match the expected contracts'
