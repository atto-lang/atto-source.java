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
tree_snapshot="$(mktemp /tmp/oreslang-committed-tree.XXXXXXXX)" || {
  echo "SOURCE_TREE_INDEX_ERROR; cannot allocate committed-tree snapshot" >&2
  exit 68
}
trap 'rm -f -- "$index_snapshot" "$tree_snapshot"' EXIT
if ! git ls-files --stage -z -- "$status_path" > "$index_snapshot"; then
  echo "SOURCE_TREE_INDEX_ERROR; index enumeration failed" >&2
  exit 68
fi
# An index enumerator may return success while silently omitting some or
# every tracked path. A byte-hash loop over that partial list would otherwise
# issue a false-positive SOURCE_TREE_VERIFIED receipt. Independently compare
# every stage-0 index entry with the immutable committed tree manifest.
if ! git ls-tree -r -z HEAD -- "$status_path" > "$tree_snapshot"; then
  echo "SOURCE_TREE_INDEX_ERROR; cannot enumerate committed source tree" >&2
  exit 68
fi
if ! python3 -I - "$index_snapshot" "$tree_snapshot" "$status_path" "$expected" <<'PYVERIFY'
import hashlib, os, pathlib, sys

def records(filename, tree):
    data = pathlib.Path(filename).read_bytes()
    if not data or len(data) > 8 * 1024 * 1024 or not data.endswith(b"\0"):
        raise ValueError("incomplete or empty Git manifest")
    rows = []
    for entry in data[:-1].split(b"\0"):
        header, sep, path = entry.partition(b"\t")
        fields = header.split(b" ")
        if not sep or not path or len(fields) != 3:
            raise ValueError("malformed Git manifest record")
        mode, middle, last = fields
        if tree:
            if middle != b"blob":
                raise ValueError("non-blob committed entry")
            sha = last
        else:
            if last != b"0":
                raise ValueError("non-zero Git index stage")
            sha = middle
        if mode not in (b"100644", b"100755") or len(sha) != 40 or any(
                c not in b"0123456789abcdef" for c in sha):
            raise ValueError("invalid committed/index entry mode or hash")
        rows.append((path, mode, sha))
    if len(set(row[0] for row in rows)) != len(rows):
        raise ValueError("duplicate Git manifest path")
    return sorted(rows)

try:
    index = records(sys.argv[1], False)
    committed = records(sys.argv[2], True)
    if index != committed:
        raise ValueError("index differs from committed tree")

    # A Git executable can return TWO successful but identically truncated
    # listings. Equality alone then attests a partial checkout. Rebuild the
    # canonical recursive Git tree from the listing and compare its digest
    # with the independently supplied immutable subtree SHA.
    source_path = os.path.normpath(os.fsencode(sys.argv[3]))
    prefix = b"" if source_path == b"." else source_path + b"/"
    nested = {}
    for path, mode, oid in committed:
        if prefix:
            if not path.startswith(prefix):
                raise ValueError("Git entry outside requested source subtree")
            relative = path[len(prefix):]
        else:
            relative = path
        parts = relative.split(b"/")
        if any(p in (b"", b".", b"..", b".git") for p in parts):
            raise ValueError("unsafe Git tree entry path")
        parent = nested
        for component in parts[:-1]:
            child = parent.setdefault(component, {})
            if not isinstance(child, dict):
                raise ValueError("file/directory Git tree collision")
            parent = child
        if parts[-1] in parent:
            raise ValueError("duplicate/colliding Git tree entry")
        parent[parts[-1]] = (mode, oid)

    def hash_tree(node):
        encoded = bytearray()
        for name, value in sorted(
                node.items(), key=lambda pair: pair[0] +
                (b"/" if isinstance(pair[1], dict) else b"")):
            if isinstance(value, dict):
                kind = b"40000"
                child_digest = hash_tree(value)
            else:
                kind, blob_sha = value
                child_digest = bytes.fromhex(blob_sha.decode("ascii"))
            encoded.extend(kind + b" " + name + b"\0" + child_digest)
        return hashlib.sha1(
            b"tree " + str(len(encoded)).encode("ascii") +
            b"\0" + encoded).digest()

    if hash_tree(nested).hex() != sys.argv[4]:
        raise ValueError("manifest does not reconstruct attested Git subtree SHA")

    # Check the actual filesystem independently of Git's untracked listing:
    # a successful-but-incomplete `git ls-files --others` must not conceal
    # additional input files or directories that Maven could later read.
    expected_files = {path for path, _mode, _blob in committed}
    expected_dirs = set()
    for path in expected_files:
        parent = os.path.dirname(path)
        while parent and parent != b".":
            expected_dirs.add(parent)
            parent = os.path.dirname(parent)

    base = os.path.normpath(os.fsencode(sys.argv[3]))
    def fail_walk(error):
        raise ValueError("cannot enumerate working-tree directory") from error
    for here, directories, files in os.walk(
            base, topdown=True, followlinks=False, onerror=fail_walk):
        if base == b"." and here == b".":
            # Only Git's metadata root is exempt, including worktree .git files.
            directories[:] = [name for name in directories if name != b".git"]
            files = [name for name in files if name != b".git"]
        for name in directories:
            path = os.path.normpath(os.path.join(here, name))
            if path not in expected_dirs:
                raise ValueError("unexpected working-tree directory")
        for name in files:
            path = os.path.normpath(os.path.join(here, name))
            if path not in expected_files:
                raise ValueError("unexpected working-tree file")
except (OSError, ValueError) as exc:
    print("SOURCE_TREE_INDEX_MISMATCH: " + str(exc), file=sys.stderr)
    sys.exit(1)
PYVERIFY
then
  echo "SOURCE_TREE_INDEX_ERROR; Git index enumeration does not match committed tree" >&2
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
  # Bash -x checks effective access, not raw mode bits. For a non-root
  # owner, a file with mode 0654 (group execute, owner not execute) may test
  # false under -x and spuriously certify a Git 100644 source when
  # core.filemode=false. Git's mode is executable when ANY x bit is set.
  if raw_mode="$(stat -c %a -- "$path" 2>/dev/null)"; then
    :
  elif raw_mode="$(stat -f %Lp -- "$path" 2>/dev/null)"; then
    :
  else
    echo "SOURCE_TREE_INDEX_ERROR; cannot inspect tracked file mode" >&2
    exit 68
  fi
  if [[ ! "$raw_mode" =~ ^[0-7]{3,4}$ ]]; then
    echo "SOURCE_TREE_INDEX_ERROR; unsupported tracked file mode" >&2
    exit 68
  fi
  exec_bits=$(( (8#$raw_mode) & 0111 ))
  if { [[ "$mode" == 100755 ]] && (( exec_bits == 0 )); } \
      || { [[ "$mode" == 100644 ]] && (( exec_bits != 0 )); }; then
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
