#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 21 -Xlint:all -Werror -d "$out" src/main/java/dev/oreslang/runtime/UnaryTransportFrame.java src/main/java/dev/oreslang/runtime/UnaryReplySlot.java src/main/java/dev/oreslang/runtime/UnaryRequestRegistry.java src/test/java/dev/oreslang/runtime/UnaryTransportFrameCheck.java src/test/java/dev/oreslang/runtime/UnaryRequestRegistryCheck.java
for run in 1 2 3; do java -cp "$out" dev.oreslang.runtime.UnaryTransportFrameCheck; java -cp "$out" dev.oreslang.runtime.UnaryRequestRegistryCheck; done
