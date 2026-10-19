#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 21 -d "$out" src/main/java/dev/oreslang/runtime/UnaryTransportFrame.java src/main/java/dev/oreslang/runtime/UnaryReplySlot.java src/test/java/dev/oreslang/runtime/UnaryTransportFrameCheck.java
for run in 1 2 3; do java -cp "$out" dev.oreslang.runtime.UnaryTransportFrameCheck; done
