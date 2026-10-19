#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 21 -Xlint:all -Werror -d "$out" src/main/java/dev/oreslang/runtime/OresBytes.java src/main/java/dev/oreslang/runtime/UnaryTransportFrame.java src/main/java/dev/oreslang/runtime/UnaryReplySlot.java src/main/java/dev/oreslang/runtime/UnaryRequestRegistry.java src/main/java/dev/oreslang/runtime/UnaryInboundRequestRegistry.java src/main/java/dev/oreslang/runtime/UnaryTcpSessionAdapter.java src/main/java/dev/oreslang/runtime/UnaryTcpMutualTlsAuthenticator.java src/test/java/dev/oreslang/runtime/OresBytesCheck.java src/test/java/dev/oreslang/runtime/UnaryTransportFrameCheck.java src/test/java/dev/oreslang/runtime/UnaryRequestRegistryCheck.java src/test/java/dev/oreslang/runtime/UnaryInboundRequestRegistryCheck.java src/test/java/dev/oreslang/runtime/UnaryTcpSessionAdapterCheck.java src/test/java/dev/oreslang/runtime/UnaryTcpMutualTlsAuthenticatorCheck.java
for run in 1 2 3; do java -cp "$out" dev.oreslang.runtime.OresBytesCheck; java -cp "$out" dev.oreslang.runtime.UnaryTransportFrameCheck; java -cp "$out" dev.oreslang.runtime.UnaryRequestRegistryCheck; java -cp "$out" dev.oreslang.runtime.UnaryInboundRequestRegistryCheck; java -cp "$out" dev.oreslang.runtime.UnaryTcpSessionAdapterCheck; java -cp "$out" dev.oreslang.runtime.UnaryTcpMutualTlsAuthenticatorCheck; done
# Real TLS 1.3, trust-chain and application authorization using disposable keys.
sh scripts/test-unary-mtls-loopback.sh
