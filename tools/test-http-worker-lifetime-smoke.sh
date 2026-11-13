#!/usr/bin/env bash
# Dependency-free, offline test against EXACT source files in this checkout.
# This is a helper-level smoke suite; it is NOT a GraalVM guest integration test.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
temp_classes="$(mktemp -d)"
trap 'rm -rf -- "$temp_classes"' EXIT

cd "$repo_root"
javac -encoding UTF-8 -d "$temp_classes" \
  src/main/java/dev/oreslang/runtime/HttpPooledWorkerAdmissionFence.java \
  src/main/java/dev/oreslang/runtime/HttpPooledWorkerGenerationRegistry.java \
  src/main/java/dev/oreslang/runtime/HttpPooledWorkerListenerSlots.java \
  tools/ci/HttpWorkerLifetimeSmoke.java
java -cp "$temp_classes" dev.oreslang.runtime.HttpWorkerLifetimeSmoke
