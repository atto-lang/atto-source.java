#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v javac >/dev/null || { echo "missing JDK javac" >&2; exit 1; }
command -v java >/dev/null || { echo "missing JDK java" >&2; exit 1; }

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$WORK" \
  "$ROOT/src/main/java/dev/oreslang/types/NamedCallBindingPlan.java" \
  "$ROOT/scripts/NamedCallPlanSmoke.java"
java -ea -cp "$WORK" NamedCallPlanSmoke
