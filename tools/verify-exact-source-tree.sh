#!/usr/bin/env bash
# Fail closed unless the checked-out source is byte-for-byte identical to the
# approved Git tree. Run before Maven, native-image, or any generated writes.
set -euo pipefail

expected="${1:-}"
source_root="${2:-.}"
if [[ ! "${expected}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: $0 EXPECTED_40_HEX_TREE_SHA [SOURCE_SUBDIR]" >&2
  exit 64
fi
if [[ "${source_root}" = "." ]]; then
  rev="HEAD^{tree}"
  status_path="."
else
  if [[ "${source_root}" = /* || "${source_root}" = *..* || "${source_root}" = *:* ]]; then
    echo "unsafe source subdirectory" >&2
    exit 64
  fi
  rev="HEAD:${source_root}"
  status_path="${source_root}"
fi

actual="$(git rev-parse --verify "${rev}")"
if [[ "$(git cat-file -t "${actual}")" != "tree" ]]; then
  echo "source revision is not a Git tree" >&2
  exit 65
fi
if [[ "${actual}" != "${expected}" ]]; then
  echo "SOURCE_TREE_MISMATCH expected=${expected} actual=${actual}" >&2
  exit 66
fi

if ! git diff --quiet HEAD -- "${status_path}" \
    || ! git diff --cached --quiet HEAD -- "${status_path}" \
    || [[ -n "$(git ls-files --others --exclude-standard -- "${status_path}")" ]]; then
  echo "SOURCE_WORKTREE_DIRTY; do not certify a modified checkout" >&2
  exit 67
fi

echo "SOURCE_TREE_VERIFIED expected=${expected} actual=${actual} source_root=${source_root}"
