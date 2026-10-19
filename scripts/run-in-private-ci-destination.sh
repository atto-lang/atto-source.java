#!/bin/sh
# Destination-bound private publisher launcher.
# Use POSIX sh (non-interactive sh does not load Bash's BASH_ENV hook).
# Never resolve the interpreter from a caller-controlled PATH.
set -eu
PATH=/usr/bin:/bin
export PATH
if [ ! -x /usr/bin/python3 ] || [ ! -x /bin/sh ]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: trusted Python or shell missing\n' >&2
  exit 1
fi
if [ "$#" -ne 11 ] || [ "${2-}" != --source-dir ] ||
   [ "${4-}" != --branch ] || [ "${6-}" != --expected-source-tree ] ||
   { [ "${8-}" != --source-pr ] && [ "${8-}" != --source-merged-pr ]; } ||
   [ "${10-}" != --expected-source-commit ]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME --expected-source-tree 40_HEX_GIT_TREE_SHA (--source-pr NUMBER|--source-merged-pr NUMBER) --expected-source-commit 40_HEX_GIT_COMMIT_SHA\n' >&2
  exit 1
fi
script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"
# Isolated Python ignores PYTHONPATH/user-site hooks. The publisher checks
# its own exact Git tree, review approvals, and root-owned GitHub CLI.
exec /usr/bin/python3 -I -B "$script_dir/publish-private-ci-source.py" "$@"
