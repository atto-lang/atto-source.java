#!/usr/bin/env bash
# Execute a real in-memory Atto Redis guest using the pinned Java/Truffle runtime.
# NO host emulation, no filesystem/network grants, no silent fallback.
set -euo pipefail
if (( $# != 1 )); then
  echo 'usage: run-atto-redis-guest.sh ABSOLUTE_SOURCE.atto' >&2
  exit 64
fi
source_file="$1"
if [[ "$source_file" != /* || "$source_file" != *.atto || ! -f "$source_file" || -L "$source_file" ]]; then
  echo 'NOT_EXECUTED: absolute regular non-symlink .atto input required' >&2
  exit 66
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
classes="$root/target/classes"
classpath_file="$root/target/conformance-classpath.txt"
if [[ ! -d "$classes" || ! -s "$classpath_file" ]]; then
  echo 'NOT_EXECUTED: build Java compiler and Maven dependency classpath first' >&2
  exit 69
fi
classpath="$(cat "$classpath_file")"
exec java -cp "$classes:$classpath" dev.oreslang.launcher.OresMain \
  --no-core-log "$source_file"
