#!/usr/bin/env bash
# Offline, source-free tests for exact-tree proof before private CI builds.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
checker="$script_dir/verify-exact-source-tree.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
git init -q
git config user.email ci-test@example.invalid
git config user.name CI-Test
mkdir source
printf 'pub fnc main(): int { return 42; }\n' > source/main.ores
printf 'unrelated\n' > other.txt
git add .
git commit -qm baseline
expected="$(git rev-parse 'HEAD:source')"

assert_status() {
  local code="$1" label="$2"
  shift 2
  local actual=0
  bash "$checker" "$@" > "$work/output" 2>&1 || actual=$?
  if [[ "$actual" != "$code" ]]; then
    printf '%s: expected exit %s, got %s\n' "$label" "$code" "$actual" >&2
    cat "$work/output" >&2
    exit 1
  fi
  if [[ "$code" == 0 ]]; then
    if ! grep -q '^SOURCE_TREE_VERIFIED ' "$work/output"; then
      printf '%s: success without source-tree receipt\n' "$label" >&2
      cat "$work/output" >&2
      exit 1
    fi
  elif grep -q '^SOURCE_TREE_VERIFIED ' "$work/output"; then
    printf '%s: failed verifier emitted a false success receipt\n' "$label" >&2
    cat "$work/output" >&2
    exit 1
  fi
  printf 'PASS %s (exit %s)\n' "$label" "$actual"
}
assert_status 0 'clean exact subtree' "$expected" source
assert_status 66 'wrong tree rejected' '0000000000000000000000000000000000000000' source
assert_status 64 'invalid tree SHA rejected' invalid source
assert_status 64 'escaping subdirectory rejected' "$expected" ../source
printf 'tampered\n' > source/main.ores
assert_status 67 'modified tracked file rejected' "$expected" source
git restore -- source/main.ores
printf 'staged\n' > source/main.ores
git add source/main.ores
assert_status 67 'staged modification rejected' "$expected" source
git restore --staged --worktree -- source/main.ores
printf 'untracked\n' > source/new.ores
assert_status 67 'untracked source rejected' "$expected" source
rm source/new.ores
# Ignored untracked compiler inputs are absent from git status, but they
# must still invalidate the attested source checkout.
printf 'source/ignored.ores\n' > .gitignore
git add .gitignore
git commit -qm ignored-input-setup
printf 'ignored yet visible to compiler\n' > source/ignored.ores
assert_status 67 'ignored untracked source rejected' "$expected" source
rm source/ignored.ores
# Index intent-to-ignore flags can suppress ordinary git diff output while
# Maven still reads changed source bytes. The proof must hash tracked bytes.
git update-index --assume-unchanged source/main.ores
printf 'hidden-changed-bytes\n' > source/main.ores
assert_status 67 'assume-unchanged byte mismatch rejected' "$expected" source
git update-index --no-assume-unchanged source/main.ores
git restore -- source/main.ores
git update-index --skip-worktree source/main.ores
printf 'hidden-skip-worktree-change\n' > source/main.ores
assert_status 67 'skip-worktree byte mismatch rejected' "$expected" source
git update-index --no-skip-worktree source/main.ores
git restore -- source/main.ores
printf 'outside subtree\n' > other.txt
assert_status 0 'other destination files excluded from subtree' "$expected" source
assert_status 67 'root proof notices non-source modifications' "$(git rev-parse 'HEAD^{tree}')" .
git restore -- other.txt
# Normal git diff ignores files explicitly marked assume-unchanged or
# skip-worktree. The checked source bytes must still match the Git tree.
git update-index --assume-unchanged source/main.ores
printf 'hidden modification\n' > source/main.ores
assert_status 67 'assume-unchanged does not bypass byte proof' "$expected" source
git update-index --no-assume-unchanged source/main.ores
git restore -- source/main.ores
git update-index --skip-worktree source/main.ores
printf 'hidden skip-worktree modification\n' > source/main.ores
assert_status 67 'skip-worktree does not bypass byte proof' "$expected" source
git update-index --no-skip-worktree source/main.ores
git restore -- source/main.ores
chmod +x source/main.ores
assert_status 67 'executable mode drift rejected' "$expected" source
chmod -x source/main.ores
# Git can ignore filesystem executable-mode drift when core.filemode=false.
# A non-root owner with mode 0654 has no owner-x permission but group-x set;
# Bash -x may return false, despite Git treating ANY x bit as executable.
# Attest raw mode bits, not effective access by the current user.
git config core.filemode false
chmod 654 source/main.ores
assert_status 67 'group-only executable bit rejected under core.filemode=false' "$expected" source
chmod 645 source/main.ores
assert_status 67 'other-only executable bit rejected under core.filemode=false' "$expected" source
chmod 641 source/main.ores
assert_status 67 'mixed permission execute drift rejected' "$expected" source
chmod 644 source/main.ores
git config core.filemode true
assert_status 0 'clean checkout recovers after hidden changes' "$expected" source
# Hardlinks to the same inode outside the source directory must be denied even
# when the tracked content and Git mode are unchanged.
ln source/main.ores "$work/outside-source-hardlink.ores"
assert_status 67 'hardlinked source cannot masquerade as exact tree' "$expected" source
rm "$work/outside-source-hardlink.ores"
assert_status 0 'single-linked file recovers' "$expected" source
# Defense-in-depth: a symlinked *parent* must also be rejected. Mock Git
# diff/untracked enumeration to be clean to exercise the raw path audit,
# rather than merely relying on Git's normal dirty-worktree checks.
mkdir -p source/nested
printf 'nested fixture\n' > source/nested/child.ores
git add source/nested/child.ores
git commit -qm nested-fixture
nested_tree="$(git rev-parse 'HEAD:source')"
assert_status 0 'nested directory baseline verified' "$nested_tree" source
mkdir "$work/redirected-directory"
cp source/nested/child.ores "$work/redirected-directory/child.ores"
rm -rf source/nested
ln -s ../redirected-directory source/nested
real_git="$(command -v git)"
mkdir -p "$work/hidden-parent-bin"
cat > "$work/hidden-parent-bin/git" <<'MOCK_HIDDEN_PARENT'
#!/usr/bin/env bash
if [[ "${1:-}" == diff ]]; then
  exit 0
fi
if [[ "${1:-}" == ls-files && "${2:-}" == --others ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_HIDDEN_PARENT
chmod +x "$work/hidden-parent-bin/git"
export REAL_GIT="$real_git"
(
  export PATH="$work/hidden-parent-bin:$PATH"
  assert_status 67 'symlinked parent denied despite matching external bytes' "$nested_tree" source
)
rm source/nested
mkdir -p source/nested
cp "$work/redirected-directory/child.ores" source/nested/child.ores
assert_status 0 'restored nested directory verified' "$nested_tree" source
# A tree can contain untrusted symlink and submodule entries.
ln -s main.ores source/shortcut.ores
git add source/shortcut.ores
git commit -qm linked
symlink_tree="$(git rev-parse 'HEAD:source')"
assert_status 68 'tracked symlink rejected' "$symlink_tree" source
git rm -q source/shortcut.ores
git commit -qm unlink
# Filenames may contain literal newlines; never split git index output on them.
odd_name="$(printf 'source/line\nbreak.ores')"
printf 'odd file\n' > "$odd_name"
git add source
git commit -qm odd-name
odd_tree="$(git rev-parse 'HEAD:source')"
assert_status 0 'newline filename verified correctly' "$odd_tree" source
git update-index --add --cacheinfo 160000,"$(git rev-parse HEAD)",source/fake-submodule
git commit -qm gitlink
link_tree="$(git rev-parse 'HEAD:source')"
assert_status 67 'gitlink/submodule rejected' "$link_tree" source
# The old process-substitution reader silently ignored an index-enumeration
# failure. Simulate a failing git ls-files --stage while other Git commands
# succeed, and confirm the verifier refuses to certify an incomplete audit.
# Remove the intentionally unsupported/missing gitlink so the status checks
# pass and the test actually reaches the index-enumeration failure.
git update-index --force-remove source/fake-submodule
git commit -qm remove-gitlink-for-index-test
clean_tree="$(git rev-parse 'HEAD:source')"
assert_status 0 'clean baseline before forced index error' "$clean_tree" source
real_git="$(command -v git)"
mkdir -p "$work/broken-index-bin"
cat > "$work/broken-index-bin/git" <<'MOCK_GIT'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-files && "${2:-}" == --stage ]]; then
  exit 99
fi
exec "$REAL_GIT" "$@"
MOCK_GIT
chmod +x "$work/broken-index-bin/git"
export REAL_GIT="$real_git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'index enumeration failure never certifies source' "$clean_tree" source
)
# Failure to enumerate untracked files is also an admission failure.
cat > "$work/broken-index-bin/git" <<'MOCK_GIT'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-files && "${2:-}" == --others ]]; then
  exit 99
fi
exec "$REAL_GIT" "$@"
MOCK_GIT
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'untracked enumeration failure cannot certify source' "$clean_tree" source
)
# A failed raw blob hash must never let a source tree pass verification.
cat > "$work/broken-index-bin/git" <<'MOCK_GIT'
#!/usr/bin/env bash
if [[ "${1:-}" == hash-object ]]; then
  exit 99
fi
exec "$REAL_GIT" "$@"
MOCK_GIT
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 99 'tracked blob hashing failure cannot certify source' "$clean_tree" source
)
# Also require successful enumeration of untracked files. A failed
# untracked-file listing must not be interpreted as an empty list.
mkdir -p "$work/broken-others-bin"
cat > "$work/broken-others-bin/git" <<'MOCK_OTHERS'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-files && "${2:-}" == --others ]]; then
  exit 98
fi
exec "$REAL_GIT" "$@"
MOCK_OTHERS
chmod +x "$work/broken-others-bin/git"
(
  export PATH="$work/broken-others-bin:$PATH"
  assert_status 68 'untracked enumeration failure never certifies source' "$clean_tree" source
)
# A successful but incomplete tracked-list response must not certify source.
cat > "$work/broken-index-bin/git" <<'MOCK_EMPTY_STAGE'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-files && "${2:-}" == --stage ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_EMPTY_STAGE
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'successful empty index manifest denied' "$clean_tree" source
)
# A successful but empty untracked-file listing cannot hide a new source file.
printf 'untracked input\n' > source/hidden-untracked.ores
cat > "$work/broken-index-bin/git" <<'MOCK_EMPTY_UNTRACKED'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-files && "${2:-}" == --others ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_EMPTY_UNTRACKED
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'successful empty untracked listing denied by inventory' "$clean_tree" source
)
rm source/hidden-untracked.ores
# Build-ignored compiler inputs also require inventory rejection.
printf 'ignored-source.ores\n' > source/.gitignore
printf 'ignored input\n' > source/ignored-source.ores
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'ignored source input denied by inventory' "$clean_tree" source
)
rm source/.gitignore source/ignored-source.ores
# A successful but empty committed-tree listing must not certify a checkout.
cat > "$work/broken-index-bin/git" <<'MOCK_EMPTY_TREE'
#!/usr/bin/env bash
if [[ "${1:-}" == ls-tree ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_EMPTY_TREE
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'successful empty committed tree denied' "$clean_tree" source
)
# A successful nonempty but PARTIAL index manifest must never certify a tree.
cat > "$work/broken-index-bin/git" <<'MOCK_PARTIAL_INDEX'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-files && "$2" == --stage ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
records = sys.stdin.buffer.read().split(b"\0")
if len(records) < 3:
    sys.exit("expected at least two tracked entries")
sys.stdout.buffer.write(records[0] + b"\0")
'
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_PARTIAL_INDEX
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'successful partial nonempty index manifest denied' "$clean_tree" source
)
# A successful partial committed-tree manifest is equally untrustworthy.
cat > "$work/broken-index-bin/git" <<'MOCK_PARTIAL_TREE'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-tree ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
records = sys.stdin.buffer.read().split(b"\0")
if len(records) < 3:
    sys.exit("expected multiple committed paths")
sys.stdout.buffer.write(records[0] + b"\0")
'
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_PARTIAL_TREE
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'successful partial nonempty committed tree denied' "$clean_tree" source
)
# Git has no entry for an empty directory, but the builder can still observe
# directory-based inputs; an empty untracked directory must fail the inventory.
mkdir -p source/injected-empty-directory
cat > "$work/broken-index-bin/git" <<'MOCK_EMPTY_OTHERS'
#!/usr/bin/env bash
if [[ "$1" == ls-files && "$2" == --others ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_EMPTY_OTHERS
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'injected empty directory denied despite hidden untracked listing' "$clean_tree" source
)
rmdir source/injected-empty-directory
# Duplicate stage-0 entries that pass casual nonempty checks must be rejected.
cat > "$work/broken-index-bin/git" <<'MOCK_DUPLICATE_INDEX'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-files && "$2" == --stage ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
data = sys.stdin.buffer.read()
records = data.split(b"\0")
assert len(records) >= 3 and not records[-1]
sys.stdout.buffer.write(data + records[0] + b"\0")
'
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_DUPLICATE_INDEX
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'duplicated stage-0 index entry denied' "$clean_tree" source
)
# A corrupted but correctly shaped mode must not be accepted as a match.
cat > "$work/broken-index-bin/git" <<'MOCK_CORRUPT_MODE'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-files && "$2" == --stage ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
data = sys.stdin.buffer.read()
assert data.startswith(b"100644 ") or data.startswith(b"100755 ")
data = (b"100755 " if data.startswith(b"100644 ") else b"100644 ") + data[7:]
sys.stdout.buffer.write(data)
'
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_CORRUPT_MODE
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'wrong staged mode denied even when blob matches' "$clean_tree" source
)
# Duplicate committed tree rows are untrustworthy, even with a healthy index.
cat > "$work/broken-index-bin/git" <<'MOCK_DUPLICATE_TREE'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-tree ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
data = sys.stdin.buffer.read()
records = data.split(b"\0")
assert len(records) >= 3 and not records[-1]
sys.stdout.buffer.write(data + records[0] + b"\0")
'
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_DUPLICATE_TREE
chmod +x "$work/broken-index-bin/git"
(
  export PATH="$work/broken-index-bin:$PATH"
  assert_status 68 'duplicated committed-tree row denied' "$clean_tree" source
)
# Two simultaneous, successful, identically incomplete Git manifests can
# agree with each other while misrepresenting HEAD's actual tree. In a wholly
# synthetic child repo, remove the omitted file and lie consistently in BOTH
# enumerators; only independent canonical tree-SHA reconstruction detects it.
(
  mkdir -p "$work/dual-manifest-oracle"
  cd "$work/dual-manifest-oracle"
  git init -q
  git config user.name CI-Test
  git config user.email ci-test@example.invalid
  mkdir source
  printf 'one\n' > source/one.ores
  printf 'two\n' > source/two.ores
  git add source
  git commit -qm two-files
  original_tree="$(git rev-parse 'HEAD:source')"
  rm source/two.ores
  mkdir -p "$work/dual-manifest-bin"
  cat > "$work/dual-manifest-bin/git" <<'MOCK_DUAL_MANIFEST'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == ls-files && "$2" == --stage ]] || [[ "$1" == ls-tree ]]; then
  "$REAL_GIT" "$@" | python3 -c '
import sys
rows = sys.stdin.buffer.read().split(b"\0")
assert len(rows) >= 3 and rows[-1] == b""
sys.stdout.buffer.write(rows[0] + b"\0")
'
  exit 0
fi
if [[ "$1" == diff ]]; then
  exit 0
fi
if [[ "$1" == ls-files && "$2" == --others ]]; then
  exit 0
fi
exec "$REAL_GIT" "$@"
MOCK_DUAL_MANIFEST
  chmod +x "$work/dual-manifest-bin/git"
  export PATH="$work/dual-manifest-bin:$PATH"
  assert_status 68 'matching partial index/tree listings cannot certify HEAD' "$original_tree" source
)
# Verify ordinary source works after removing the adversarial Git shim.
assert_status 0 'clean source remains valid after index failure test' "$clean_tree" source
# Positive witness for 100755: the new raw-mode comparison must accept a
# committed executable file. A filesystem downgrade remains invalid even if
# the Git index is configured to ignore filemode changes.
chmod 755 source/main.ores
git add source/main.ores
git commit -qm committed-executable-mode
executable_tree="$(git rev-parse 'HEAD:source')"
assert_status 0 'committed executable file accepted' "$executable_tree" source
git config core.filemode false
chmod 644 source/main.ores
assert_status 67 'committed executable file downgrade rejected' "$executable_tree" source
chmod 755 source/main.ores
git config core.filemode true
assert_status 0 'executable-mode checkout restored' "$executable_tree" source
echo 'SOURCE_TREE_GATE_TESTS_PASS'
