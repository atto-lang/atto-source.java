#!/bin/sh
# V3 dispatcher; only data-only admission in Python may authorize private source.
set -eu
PATH=/usr/bin:/bin
export PATH
if [ ! -x /usr/bin/python3 ] || [ ! -x /bin/sh ]; then
  printf '%s\n' 'PRIVATE_CI_DESTINATION_REJECTED: trusted Python or shell unavailable' >&2
  exit 1
fi
if [ "$#" -ne 9 ] || [ "${2-}" != --source-dir ] || \
   [ "${4-}" != --source-commit ] || [ "${6-}" != --source-pr ] || \
   [ "${8-}" != --test-plan ]; then
  printf '%s\n' 'PRIVATE_CI_DESTINATION_REJECTED: usage: run-in-private-ci-destination.sh owner/repo --source-dir DIRECTORY --source-commit FULL_40_HEX_SHA --source-pr POSITIVE_INTEGER --test-plan ci/test-plans/NAME.json' >&2
  exit 1
fi
script_dir="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"
exec /usr/bin/python3 -I -B "$script_dir/publish-private-ci-source-v3.py" "$@"
