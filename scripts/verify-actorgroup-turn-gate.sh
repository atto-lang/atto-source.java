#!/usr/bin/env bash
# Dependency-free fail-closed ActorGroup CPU-turn gate verification.
# This uses the ACTUAL local checkout source, never a reconstructed fixture.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"
command -v javac >/dev/null 2>&1 || {
  printf 'ERROR: JDK 21+ javac is required\n' >&2
  exit 1
}
command -v java >/dev/null 2>&1 || {
  printf 'ERROR: JDK 21+ java is required\n' >&2
  exit 1
}
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
javac -d "$work" \
  src/main/java/dev/oreslang/runtime/ActorGroupTurnGate.java \
  tests/standalone/dev/oreslang/runtime/ActorGroupTurnGateStandaloneSmoke.java
java -ea -cp "$work" dev.oreslang.runtime.ActorGroupTurnGateStandaloneSmoke
printf 'Gate-only standalone test passed; ActorRuntime/GraalVM integration still requires Maven CI.\n'
