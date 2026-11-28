#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
java -version
javac -Xlint:all -Werror -d "$out" "$root"/verification/control-plane-arena-v1/*.java
java -cp "$out" ControlPlaneArenaProbe
