#!/bin/sh
# Standalone exact-source host-value proof. Does not execute guest .atto code.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

javac --release 17 -Xlint:all -Werror -d "$tmp" \
  "$root/src/main/java/dev/oreslang/runtime/ActorFailureTrace.java" \
  "$root/src/main/java/dev/oreslang/runtime/SupervisorFailureInbox.java" \
  "$root/src/test/java/dev/oreslang/runtime/SupervisorFailureValueProbe.java" \
  "$root/src/test/java/dev/oreslang/runtime/InboxModelFuzz.java"

java -cp "$tmp" dev.oreslang.runtime.SupervisorFailureValueProbe
java -cp "$tmp" dev.oreslang.runtime.InboxModelFuzz
