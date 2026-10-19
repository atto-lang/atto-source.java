#!/bin/bash
# Destination-bound private CI publisher; never execute a caller-supplied command.
set -euo pipefail
# Never resolve the launcher or Python interpreter via caller-controlled PATH.
# This wrapper must itself be invoked via /bin/bash or its absolute shebang.
export PATH=/usr/bin:/bin
hash -r
if [[ ! -x /usr/bin/python3 || ! -x /bin/bash ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: trusted Python or shell missing\n' >&2
  exit 1
fi
if [[ $# -ne 11 || "${2:-}" != --source-dir || "${4:-}" != --branch || "${6:-}" != --expected-source-tree || ( "${8:-}" != --source-pr && "${8:-}" != --source-merged-pr ) || "${10:-}" != --expected-source-commit ]]; then
  printf 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --branch ci/oreslang-NAME --expected-source-tree 40_HEX_GIT_TREE_SHA (--source-pr NUMBER|--source-merged-pr NUMBER) --expected-source-commit 40_HEX_GIT_COMMIT_SHA\n' >&2
  exit 1
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# Isolated mode ignores PYTHONPATH/user-site imports and environment hooks.
# The Python publisher independently enforces GitHub CLI provenance.
exec /usr/bin/python3 -I -B "$script_dir/publish-private-ci-source.py" "$@"
