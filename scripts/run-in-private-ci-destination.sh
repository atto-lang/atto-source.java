#!/bin/sh
# Destination-bound private CI publisher; never execute a caller-supplied command.
# POSIX shell avoids Bash's inherited BASH_ENV startup execution.
set -eu
PATH=/usr/bin:/bin:/usr/local/bin
export PATH
unset BASH_ENV ENV BASHOPTS SHELLOPTS CDPATH GLOBIGNORE PROMPT_COMMAND
unset PYTHONPATH PYTHONHOME PYTHONINSPECT
unset LD_PRELOAD LD_LIBRARY_PATH DYLD_INSERT_LIBRARIES DYLD_LIBRARY_PATH
if [ "$#" -ne 9 ] || [ "${2:-}" != --source-dir ] || [ "${4:-}" != --branch ] || [ "${6:-}" != --expected-source-tree ] || [ "${8:-}" != --expected-source-commit ]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME --expected-source-tree HEX40 --expected-source-commit HEX40\n' >&2
  exit 1
fi
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
exec python3 -I "$script_dir/publish-private-ci-source.py" "$@"
