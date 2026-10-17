#!/usr/bin/env sh
# Executes the exact JVM host routing plan without Maven, Graal or network.
# This verifies topology/selection ONLY, not compiled Oreslang guest routing.
set -eu
cd "$(dirname "$0")/.."
command -v javac >/dev/null 2>&1 || {
  echo "actor pipeline host test requires javac" >&2; exit 1;
}
command -v java >/dev/null 2>&1 || {
  echo "actor pipeline host test requires java" >&2; exit 1;
}
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT HUP INT TERM
javac --release 21 -Xlint:all -Werror -d "$temp" \
  src/main/java/dev/oreslang/runtime/ActorPipelineRoutePlan.java \
  src/main/java/dev/oreslang/runtime/ActorPipelineRouteSession.java \
  src/test/java/dev/oreslang/runtime/ActorPipelineRoutePlanStandaloneSmoke.java \
  src/test/java/dev/oreslang/runtime/ActorPipelineRouteSessionSmoke.java
java -cp "$temp" dev.oreslang.runtime.ActorPipelineRoutePlanStandaloneSmoke
java -cp "$temp" dev.oreslang.runtime.ActorPipelineRouteSessionSmoke
