#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 21 -sourcepath "$root/tests/host/udp" -d "$out" \
  "$root/src/main/java/dev/oreslang/runtime/UdpDatagramKernel.java" \
  "$root/src/main/java/dev/oreslang/runtime/AsyncNioUdp.java" \
  "$root/tests/host/udp/UdpBridgeHarness.java"
java -cp "$out" dev.oreslang.runtime.UdpBridgeHarness
