#!/usr/bin/env bash
# Real JDK21 socket proof for the narrow trusted host-only loopback transport.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" \
  src/main/java/dev/oreslang/runtime/ProxyOutboundLoopback.java \
  src/test/java/dev/oreslang/runtime/ProxyOutboundLoopbackCheck.java \
  src/test/java/dev/oreslang/runtime/ProxyLoopbackForwardingWireCheck.java
for attempt in 1 2 3 4 5; do
  java -cp "$out" dev.oreslang.runtime.ProxyOutboundLoopbackCheck
  java -cp "$out" dev.oreslang.runtime.ProxyLoopbackForwardingWireCheck
done
