#!/usr/bin/env bash
# Source-free synthetic checkout tests, not a Java compiler or guest execution.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/mockbin"
real_git="$(command -v git)"
cat > "$tmp/mockbin/git" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1-}" == '-C' ]]; then
  case "${FAIL_GIT_KIND:-}" in
    status) [[ "${3-}" == status ]] && exit 75 ;;
    flags) [[ "${3-}" == ls-files && "${4-}" == -v ]] && exit 76 ;;
    blobs) [[ "${3-}" == ls-files && "${4-}" == --stage ]] && exit 77 ;;
    ignored) [[ "${3-}" == ls-files && "${4-}" == --others ]] && exit 78 ;;
  esac
fi
exec "$REAL_GIT" "$@"
MOCK
chmod +x "$tmp/mockbin/git"
make_repo() {
  rm -rf "$tmp/checkout"
  mkdir -p "$tmp/checkout/scripts"
  cp "$root/scripts/verify-source-tree.sh" "$tmp/checkout/scripts/verify-source-tree.sh"
  chmod +x "$tmp/checkout/scripts/verify-source-tree.sh"
  printf 'pub routine main(): int { return 42; }\n' > "$tmp/checkout/main.ores"
  git -C "$tmp/checkout" init -q
  git -C "$tmp/checkout" add .
  git -C "$tmp/checkout" -c user.name=SourceAudit -c user.email=source-audit@invalid.example commit -qm initial
  sha="$(git -C "$tmp/checkout" rev-parse HEAD)"
  checker="$tmp/checkout/scripts/verify-source-tree.sh"
}
pass=0
expect_success() {
  local name="$1"
  local stdout
  stdout="$($checker "$sha")" || { echo "FAIL $name" >&2; exit 1; }
  [[ "$stdout" == "verified_java_source_sha=$sha" ]] || { echo "FAIL $name output: $stdout" >&2; exit 1; }
  ((pass+=1)); echo "PASS $name"
}
expect_denied() {
  local name="$1" expected="$2" msg="$3"
  local observed=0
  "$checker" "$expected" > "$tmp/output" 2> "$tmp/error" || observed="$?"
  if [[ "$observed" != 65 ]] || ! grep -Fq "$msg" "$tmp/error" || [[ -s "$tmp/output" ]]; then
    echo "FAIL $name: code=$observed" >&2; cat "$tmp/error" >&2; exit 1
  fi
  ((pass+=1)); echo "PASS $name"
}
make_repo
expect_success 'clean exact-head checkout'
mkdir -p "$tmp/checkout/target"
printf 'binary\n' > "$tmp/checkout/target/result.bin"
printf 'target/\n' >> "$tmp/checkout/.git/info/exclude"
expect_success 'ignored generated build output confined to target'
make_repo
expected="0000000000000000000000000000000000000000"
expect_denied 'wrong pinned head SHA' "$expected" 'source commit mismatch'
make_repo
printf 'tampered\n' > "$tmp/checkout/main.ores"
expect_denied 'mutated tracked source' "$sha" 'dirty source checkout'
make_repo
git -C "$tmp/checkout" update-index --skip-worktree main.ores
printf 'tampered\n' > "$tmp/checkout/main.ores"
[[ -z "$(git -C "$tmp/checkout" status --porcelain)" ]] || { echo 'FAIL hidden skip-worktree setup' >&2; exit 1; }
expect_denied 'hidden skip-worktree mutation' "$sha" 'unverified Git index flags'
make_repo
git -C "$tmp/checkout" update-index --assume-unchanged main.ores
printf 'tampered\n' > "$tmp/checkout/main.ores"
[[ -z "$(git -C "$tmp/checkout" status --porcelain)" ]] || { echo 'FAIL hidden assume-unchanged setup' >&2; exit 1; }
expect_denied 'hidden assume-unchanged mutation' "$sha" 'unverified Git index flags'
make_repo
printf 'ignored.ores\n' >> "$tmp/checkout/.git/info/exclude"
printf 'pub routine malicious():int{return 1;}\n' > "$tmp/checkout/ignored.ores"
expect_denied 'ignored source injection' "$sha" 'ignored untracked input'
make_repo
ln -s main.ores "$tmp/checkout/not_pinned.ores"
git -C "$tmp/checkout" add not_pinned.ores
git -C "$tmp/checkout" -c user.name=SourceAudit -c user.email=source-audit@invalid.example commit -qm 'symlink'
sha="$(git -C "$tmp/checkout" rev-parse HEAD)"
expect_denied 'committed symlink input' "$sha" 'unverified tracked file mode'
for failure in status flags blobs ignored; do
  make_repo
  observed=0
  REAL_GIT="$real_git" FAIL_GIT_KIND="$failure" PATH="$tmp/mockbin:$PATH" \
    "$checker" "$sha" > "$tmp/output" 2> "$tmp/error" || observed="$?"
  if [[ "$observed" != 65 || -s "$tmp/output" ]] || ! grep -Fq 'inventory failed' "$tmp/error"; then
    echo "FAIL forced Git $failure inventory error: code=$observed" >&2
    cat "$tmp/error" >&2; exit 1
  fi
  ((pass+=1)); echo "PASS forced Git $failure inventory error"
done
printf 'PASS %d source-free admission scenarios; no Java/Graal guest executed\n' "$pass"
