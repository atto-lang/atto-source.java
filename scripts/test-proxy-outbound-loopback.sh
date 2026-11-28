#!/usr/bin/env bash
# Real JDK21+ socket proof for the narrow trusted host-only loopback transport.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 21 -d "$out" \
  src/main/java/dev/oreslang/runtime/ProxyOutboundLoopback.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundLoopbackCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyLoopbackForwardingWireCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundLifecycleCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundHandoffRaceCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundStartCloseRaceCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundHeaderBudgetCheck.java

for attempt in 1 2 3 4 5; do
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundLoopbackCheck
  java -cp "$out" dev.oreslang.runtime.ProxyLoopbackForwardingWireCheck
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundLifecycleCheck
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundHandoffRaceCheck
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundStartCloseRaceCheck
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundHeaderBudgetCheck
done
