#!/usr/bin/env bash
# Conservative Native Image PGO CLI-capability probe. Does NOT assert that
# the installed edition/license can instrument, train, or rebuild this app.
set -euo pipefail
usage() { echo 'Usage: scripts/native-pgo-preflight.sh /absolute/path/to/native-image' >&2; exit 64; }
[[ $# == 1 && "$1" == /* ]] || usage
tool="$1"
if [[ ! -f "$tool" || ! -x "$tool" ]]; then
  echo 'UNSUPPORTED: native-image executable missing or not executable' >&2
  exit 78
fi
if ! version="$("$tool" --version 2>&1)"; then
  echo 'UNSUPPORTED: native-image --version failed' >&2
  exit 78
fi
# Ask for the help text of this exact executable rather than assuming flags
# based on the Maven dependency version or the presence of a JVM JIT.
if ! help="$("$tool" --help-extra 2>&1)"; then
  echo 'UNSUPPORTED: native-image --help-extra failed' >&2
  exit 78
fi
if [[ "$help" != *--pgo-instrument* || "$help" != *--pgo=* ]]; then
  echo 'UNSUPPORTED: this Native Image help does not advertise both PGO options' >&2
  exit 78
fi
printf 'CLI_CANDIDATE_ONLY: Native Image advertises instrument and consume-profile flags\n'
printf 'Native Image version (untrusted tool output):\n%s\n' "$version"
printf 'Next gates: real instrumented build, nonempty .iprof, held-out correctness, profile rebuild, edition/license checks and repeated benchmarks.\n'
