#!/usr/bin/env bash
# Bash-only negative controls; all Git mutations are in a temporary scratch repo.
set -euo pipefail
gate="$(cd "$(dirname "$0")" && pwd)/verify-critical-guest-inputs.sh"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cd "$tmp"
git init -q
git config user.name tester
git config user.email tester@example.invalid
mkdir -p src/main/java/test src/test/java/test .github/workflows tools examples .mvn
printf 'class Example {}\n' > src/main/java/test/Example.java
printf 'class Test {}\n' > src/test/java/test/Test.java
printf '<project/>\n' > pom.xml
printf 'class Verifier {}\n' > tools/VerifyAssuranceSurefire.java
printf 'actor {}\n' > examples/critical-actor-checked-v1.atto
printf 'actor {}\n' > examples/critical-actor-checked-v1.ores
printf 'actor {}\n' > examples/critical-actor-early-return-v1.atto
printf 'actor {}\n' > examples/critical-actor-early-return-v1.ores
printf 'name: checks\n' > .github/workflows/critical-suspends-java-admission.yml
printf '<settings/>\n' > .mvn/settings.xml
git add . && git commit -qm baseline
export EXPECTED_SOURCE_HEAD="$(git rev-parse HEAD)"
count=0
pass(){
  bash "$gate" > "$tmp/out"
  grep -F SCOPED_JAVA_INPUTS_MATCH_HEAD "$tmp/out" >/dev/null
  count=$((count+1))
}
reject(){
  local code="$1"
  if bash "$gate" >"$tmp/out" 2>&1; then
    echo "UNEXPECTED_PASS: $code" >&2; exit 1
  fi
  grep -F "$code" "$tmp/out" >/dev/null || {
    cat "$tmp/out" >&2; echo "WRONG_REJECTION: $code" >&2; exit 1;
  }
  count=$((count+1))
}
pass
EXPECTED_SOURCE_HEAD="$(printf 'a%.0s' {1..40})" reject HEAD_MISMATCH
printf 'tamper\n' >> src/main/java/test/Example.java
reject TRACKED_INPUT_DIRTY
git checkout -q -- src/main/java/test/Example.java
git update-index --assume-unchanged src/main/java/test/Example.java
printf 'assume-unchanged-mutation\n' >> src/main/java/test/Example.java
reject HEAD_BLOB_MISMATCH
git update-index --no-assume-unchanged src/main/java/test/Example.java
git checkout -q -- src/main/java/test/Example.java
git update-index --skip-worktree src/main/java/test/Example.java
printf 'skip-worktree-mutation\n' >> src/main/java/test/Example.java
reject HEAD_BLOB_MISMATCH
git update-index --no-skip-worktree src/main/java/test/Example.java
git checkout -q -- src/main/java/test/Example.java
printf 'class Extra {}\n' > src/main/java/test/Extra.java
reject UNTRACKED_INPUT
rm src/main/java/test/Extra.java
printf '*.java\n' > .gitignore
printf 'class Ignored {}\n' > src/main/java/test/Ignored.java
reject UNTRACKED_INPUT
git add .gitignore && git commit -qm ignore
export EXPECTED_SOURCE_HEAD="$(git rev-parse HEAD)"
reject UNTRACKED_INPUT
rm src/main/java/test/Ignored.java
rm examples/critical-actor-checked-v1.atto
reject TRACKED_INPUT_DIRTY
git checkout -q -- examples/critical-actor-checked-v1.atto
rm .mvn/settings.xml
ln -s /etc/passwd .mvn/settings.xml
reject TRACKED_INPUT_DIRTY
rm .mvn/settings.xml
git checkout -q -- .mvn/settings.xml
printf '<extension/>\n' > .mvn/extensions.xml
reject UNTRACKED_INPUT
rm .mvn/extensions.xml
# A successful Git command that silently truncates its NUL-delimited output
# must not allow a partial source manifest to pass.
mkdir -p "$tmp/gitshim"
real_git="$(command -v git)"
cat > "$tmp/gitshim/git" <<SHIM
#!/usr/bin/env bash
if [[ "\$1" == ls-files && "\$2" == -z ]]; then
  "$real_git" "\$@" | head -c -1
else
  exec "$real_git" "\$@"
fi
SHIM
chmod +x "$tmp/gitshim/git"
PATH="$tmp/gitshim:$PATH" reject INDEX_TREE_PATH_MISMATCH
mkdir -p "$tmp/findshim"
cat > "$tmp/findshim/find" <<'SHIM'
#!/usr/bin/env bash
exit 7
SHIM
chmod +x "$tmp/findshim/find"
PATH="$tmp/findshim:$PATH" reject WALK_ENUMERATION_FAILED
pass
printf 'PASS scoped original-head input integrity controls: %d\n' "$count"
