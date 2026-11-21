#!/usr/bin/env sh
# Compile and execute the exact Java correlation plan and host message path.
# This checks host-only routing metadata, not Oreslang guest compilation.
set -eu
cd "$(dirname "$0")/.."
command -v javac >/dev/null 2>&1 || { echo "requires javac" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "requires java" >&2; exit 1; }
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT HUP INT TERM
javac --release 21 -Xlint:all -Werror -d "$out" \
  src/main/java/dev/oreslang/runtime/ActorPipelineRoutePlan.java \
  src/main/java/dev/oreslang/runtime/ActorPipelineRouteSession.java \
  src/test/java/dev/oreslang/runtime/ActorPipelineRouteSessionSmoke.java
java -cp "$out" dev.oreslang.runtime.ActorPipelineRouteSessionSmoke
