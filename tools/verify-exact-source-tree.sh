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

untracked="$(git ls-files --others -- "${status_path}")" || {
  echo "SOURCE_TREE_INDEX_ERROR; cannot enumerate untracked files" >&2
  exit 68
}
if ! git diff --quiet HEAD -- "${status_path}" \
    || ! git diff --cached --quiet HEAD -- "${status_path}" \
    || [[ -n "$untracked" ]]; then
  echo "SOURCE_WORKTREE_DIRTY; do not certify a modified checkout" >&2
  exit 67
fi

# A Git index can mark files assume-unchanged or skip-worktree, making
# git diff --quiet appear clean even when bytes read by Maven have changed.
# Independently check raw bytes against the attested Git blobs. Reject
# symlinks/gitlinks and unmerged stages instead of following external content.
# NUL-delimited index records preserve filenames containing whitespace/newlines.
# Process substitution silently loses the producer's exit status. A failed
# git ls-files --stage would otherwise certify an uninspected source tree.
# Capture a NUL-delimited index listing in a separate temporary file and
# require successful enumeration before validating any tracked bytes.
index_snapshot="$(mktemp /tmp/oreslang-source-index.XXXXXXXX)" || {
  echo "SOURCE_TREE_INDEX_ERROR; cannot allocate index snapshot" >&2
  exit 68
}
trap 'rm -f -- "$index_snapshot"' EXIT
if ! git ls-files --stage -z -- "$status_path" > "$index_snapshot"; then
  echo "SOURCE_TREE_INDEX_ERROR; index enumeration failed" >&2
  exit 68
fi
tab="$(printf '\t')"
while IFS= read -r -d '' entry; do
  metadata="${entry%%"$tab"*}"
  path="${entry#*"$tab"}"
  read -r mode blob stage <<< "$metadata"
  if [[ "$stage" != 0 || ( "$mode" != 100644 && "$mode" != 100755 ) ]]; then
    echo "SOURCE_TREE_UNSAFE_ENTRY; only stage-0 regular files are allowed" >&2
    exit 68
  fi
  if [[ ! -f "$path" || -L "$path" ]]; then
    echo "SOURCE_WORKTREE_DIRTY; missing or non-regular tracked file" >&2
    exit 67
  fi
  # Reject path redirection through an untracked symlinked ancestor, even
  # when the bytes at the resolved leaf happen to match the committed blob.
  prefix=""
  remaining="$path"
  while [[ "$remaining" == */* ]]; do
    component="${remaining%%/*}"
    if [[ -z "$component" || "$component" == . || "$component" == .. ]]; then
      echo "SOURCE_TREE_UNSAFE_ENTRY; unsafe tracked path component" >&2
      exit 68
    fi
    prefix="${prefix:+$prefix/}$component"
    if [[ -L "$prefix" || ! -d "$prefix" ]]; then
      echo "SOURCE_WORKTREE_DIRTY; tracked path has a non-directory or symlink parent" >&2
      exit 67
    fi
    remaining="${remaining#*/}"
  done
  # A source file hardlinked to another location can be altered without a
  # write through the certified subtree. Require a single-linked file, like
  # the source-free no-follow verifier. Ubuntu's stat is used in GitHub CI;
  # BSD stat is supported for local development.
  if link_count="$(stat -c %h -- "$path" 2>/dev/null)"; then
    :
  elif link_count="$(stat -f %l -- "$path" 2>/dev/null)"; then
    :
  else
    echo "SOURCE_TREE_INDEX_ERROR; cannot inspect tracked file link count" >&2
    exit 68
  fi
  if [[ "$link_count" != 1 ]]; then
    echo "SOURCE_WORKTREE_DIRTY; multiply-linked source file forbidden" >&2
    exit 67
  fi
  if [[ "$mode" == 100755 && ! -x "$path" ]] \
      || [[ "$mode" == 100644 && -x "$path" ]]; then
    echo "SOURCE_WORKTREE_DIRTY; tracked file mode differs from attested tree" >&2
    exit 67
  fi
  observed_blob="$(git hash-object --no-filters -- "$path")"
  if [[ "$observed_blob" != "$blob" ]]; then
    echo "SOURCE_WORKTREE_DIRTY; tracked bytes differ from attested Git blob" >&2
    exit 67
  fi
done < "$index_snapshot"

echo "SOURCE_TREE_VERIFIED expected=${expected} actual=${actual} source_root=${source_root}"
