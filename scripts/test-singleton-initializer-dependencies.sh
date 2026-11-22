#!/usr/bin/env bash
# Exact-source narrow JVM smoke. NOT full Maven, Truffle guest or Native Image.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
src="$repo_root/src/main/java/dev/oreslang/runtime"
tests="$repo_root/src/test/java/dev/oreslang/runtime"
javac -Xlint:all -Werror -d "$tmp" \
  "$src/SingletonDependencyGraph.java" \
  "$src/SingletonSharedDomain.java" \
  "$tests/SingletonDependencyGraphSmoke.java" \
  "$tests/SingletonCrossThreadCycleSmoke.java"
java -cp "$tmp" dev.oreslang.runtime.SingletonDependencyGraphSmoke
java -cp "$tmp" dev.oreslang.runtime.SingletonCrossThreadCycleSmoke
