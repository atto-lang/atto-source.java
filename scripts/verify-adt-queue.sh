#!/usr/bin/env bash
# Dependency-free exact-repository-source smoke test of the async match queue.
# Does not replace Maven, GraalVM Native Image, or LLVM parity verification.
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v javac >/dev/null 2>&1; then
  echo "A JDK (21 or newer) is required for the ADT queue smoke" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "The Java runtime is missing" >&2
  exit 2
fi
workspace="$(mktemp -d)"
trap 'rm -rf "$workspace"' EXIT
javac -d "$workspace" \
  src/main/java/dev/oreslang/runtime/MatchContinuationQueue.java \
  src/test/java/dev/oreslang/runtime/MatchContinuationQueueSmoke.java
java -cp "$workspace" dev.oreslang.runtime.MatchContinuationQueueSmoke
