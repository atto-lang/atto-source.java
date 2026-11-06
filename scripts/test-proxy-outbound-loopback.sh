#!/usr/bin/env bash
# Real JDK21+ socket proof for the narrow trusted host-only loopback transport.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 21 -d "$out" \
  src/main/java/dev/atto/runtime/ProxyOutboundLoopback.java \
  src/test/java/dev/atto/runtime/ProxyOutboundLoopbackCheck.java \
  src/test/java/dev/atto/runtime/ProxyLoopbackForwardingWireCheck.java \
  src/test/java/dev/atto/runtime/ProxyOutboundLifecycleCheck.java \
  src/test/java/dev/atto/runtime/ProxyOutboundHandoffRaceCheck.java \
  src/test/java/dev/atto/runtime/ProxyOutboundStartCloseRaceCheck.java \
  src/test/java/dev/atto/runtime/ProxyOutboundHeaderBudgetCheck.java

for attempt in 1 2 3 4 5; do
  java -cp "$out" dev.atto.runtime.ProxyOutboundLoopbackCheck
  java -cp "$out" dev.atto.runtime.ProxyLoopbackForwardingWireCheck
  java -cp "$out" dev.atto.runtime.ProxyOutboundLifecycleCheck
  java -cp "$out" dev.atto.runtime.ProxyOutboundHandoffRaceCheck
  java -cp "$out" dev.atto.runtime.ProxyOutboundStartCloseRaceCheck
  java -cp "$out" dev.atto.runtime.ProxyOutboundHeaderBudgetCheck
done
