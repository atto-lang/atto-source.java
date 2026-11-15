#!/usr/bin/env bash
# Standalone Java host registry gate. This is NOT a .ores compiler/guest proof.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
command -v timeout >/dev/null || { echo 'GNU timeout required' >&2; exit 2; }
command -v javac >/dev/null || { echo 'javac required' >&2; exit 2; }
command -v java >/dev/null || { echo 'java required' >&2; exit 2; }
# Source-level integration guard: these assertions do not replace Maven
# compiler/Truffle runtime execution, which is a separate acceptance gate.
grep -Fq 'this.workflowHostServices = WorkflowHostServiceRegistry.forNewContext(' \
  src/main/java/dev/oreslang/runtime/OresContext.java
grep -Fq '!isolatePolicy.adversarial()' \
  src/main/java/dev/oreslang/runtime/OresContext.java
if grep -Eq 'public[[:space:]]+WorkflowHostServiceRegistry[[:space:]]+workflowHostServices\(' \
  src/main/java/dev/oreslang/runtime/OresContext.java; then
  echo 'unsafe public host service registry getter' >&2
  exit 2
fi
grep -Fq 'context.initialize(OresLanguage.ID)' \
  src/main/java/dev/oreslang/runtime/LinkedProgramRunner.java
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
javac -d "$tmp" \
 src/main/java/dev/oreslang/runtime/WorkflowHostServiceRegistry.java \
 src/test/java/dev/oreslang/runtime/WorkflowHostServiceRegistryTest.java
timeout --kill-after=2s 25s java -cp "$tmp" \
 dev.oreslang.runtime.WorkflowHostServiceRegistryTest
