#!/usr/bin/env bash
# Standalone Java host registry gate. This is NOT a .ores compiler/guest proof.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
command -v timeout >/dev/null || { echo 'GNU timeout required' >&2; exit 2; }
command -v javac >/dev/null || { echo 'javac required' >&2; exit 2; }
command -v java >/dev/null || { echo 'java required' >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
javac -d "$tmp" \
 src/main/java/dev/oreslang/runtime/WorkflowHostServiceRegistry.java \
 src/test/java/dev/oreslang/runtime/WorkflowHostServiceRegistryTest.java
timeout --kill-after=2s 25s java -cp "$tmp" \
 dev.oreslang.runtime.WorkflowHostServiceRegistryTest
