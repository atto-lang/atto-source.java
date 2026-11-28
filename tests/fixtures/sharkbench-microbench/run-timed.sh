#!/usr/bin/env bash
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
if [[ $# != 3 ]]; then echo "usage: run-timed.sh EXPECTED_HEAD EXPECTED_TREE NEW_OUTPUT_DIR" >&2; exit 2; fi
head="$1"; tree="$2"; out="$(realpath -m "$3")"
[[ "$head" =~ ^[a-f0-9]{40}$ && "$tree" =~ ^[a-f0-9]{40}$ ]]
test "$(git rev-parse HEAD)" = "$head"
test "$(git rev-parse 'HEAD^{tree}')" = "$tree"
test -z "$(git status --porcelain --untracked-files=all)"
case "$out/" in "$root/"*) echo "output must be outside checkout" >&2; exit 2;; esac
mkdir "$out"
java -XshowSettings:properties -version > "$out/java.txt" 2>&1
grep -Eq '^[[:space:]]*java.specification.version = 25$' "$out/java.txt"
printf 'COMPILER_HEAD=%s\nCOMPILER_TREE=%s\n' "$head" "$tree" > "$out/identity.txt"
uname -a > "$out/host.txt"
lscpu >> "$out/host.txt"
bash tests/fixtures/sharkbench-admission/maven-retry.sh -B -ntp compile dependency:build-classpath -Dmdep.outputFile=target/sharkbench-microbench-classpath.txt > "$out/build.log" 2>&1
test -s target/sharkbench-microbench-classpath.txt
cp="$root/target/classes:$(cat target/sharkbench-microbench-classpath.txt)"
here=tests/fixtures/sharkbench-microbench
for name in json utf8 bytes; do
  case "$name" in json) expected=22000;; *) expected=8000;; esac
  file="$here/timed-$name.atto"
  printf 'GUEST_BLOB=%s %s\n' "$(git hash-object "$file")" "$file" >> "$out/identity.txt"
  for fork in 1 2 3; do
    stem="$out/$name-$fork"
    timeout --kill-after=5s 120s java -cp "$cp" dev.oreslang.launcher.OresMain --no-core-log --permission-check=runtime "$file" > "$stem.stdout" 2> "$stem.stderr"
    python3 -B "$here/parse-fork.py" "$expected" "$stem.stdout" > "$stem.json"
  done
done
test "$(git rev-parse HEAD)" = "$head"
test "$(git rev-parse 'HEAD^{tree}')" = "$tree"
test -z "$(git status --porcelain --untracked-files=all)"
printf 'PASS timed guest diagnostic receipts; not an official throughput score\n' | tee "$out/COMPLETE"
