#!/usr/bin/env bash
# Compiled guest correctness admission, no throughput score.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
test -z "$(git status --porcelain --untracked-files=all)"
head="$(git rev-parse HEAD)"
tree="$(git rev-parse 'HEAD^{tree}')"
printf 'COMPILER_HEAD=%s COMPILER_TREE=%s\n' "$head" "$tree"
java -XshowSettings:properties -version 2>&1 | grep -Eq '^[[:space:]]*java.specification.version = 25$'
bash tests/fixtures/sharkbench-admission/maven-retry.sh -B -ntp compile dependency:build-classpath -Dmdep.outputFile=target/sharkbench-microbench-classpath.txt
test -s target/sharkbench-microbench-classpath.txt
cp="$root/target/classes:$(cat target/sharkbench-microbench-classpath.txt)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
run_one() {
  local name="$1" expected="$2" file="tests/fixtures/sharkbench-microbench/$1.atto"
  printf 'GUEST_BLOB=%s %s\n' "$(git hash-object "$file")" "$file"
  timeout --kill-after=5s 120s java -cp "$cp" dev.oreslang.launcher.OresMain --no-core-log --permission-check=runtime "$file" > "$tmp/out" 2> "$tmp/err" || { cat "$tmp/err" >&2; cat "$tmp/out" >&2; return 1; }
  printf '%s\n' "$expected" > "$tmp/expected"
  diff -u "$tmp/expected" "$tmp/out"
  printf 'PASS %s checksum=%s\n' "$name" "$expected"
}
run_one json 22000
run_one utf8 8000
run_one bytes 8000
test "$(git rev-parse HEAD)" = "$head"
test "$(git rev-parse 'HEAD^{tree}')" = "$tree"
test -z "$(git status --porcelain --untracked-files=all)"
printf 'PASS compiled guest checksum admission only; no score\n'
