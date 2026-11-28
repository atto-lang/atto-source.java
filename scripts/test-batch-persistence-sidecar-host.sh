#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
javac -Xlint:all -Werror -d "$test_dir" \
  src/main/java/dev/oreslang/runtime/persistence/BatchPersistenceSidecar.java \
  src/test/java/dev/oreslang/runtime/persistence/BatchPersistenceSidecarTestMain.java
java -cp "$test_dir" dev.oreslang.runtime.persistence.BatchPersistenceSidecarTestMain
# The ActorPersistenceBridge is compiled by the full Maven/Graal build; this
# narrow host test MUST NOT be promoted to native source guest parity.
