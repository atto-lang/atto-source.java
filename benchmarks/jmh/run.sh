#!/usr/bin/env bash
# Opt-in host diagnostic; correctness CI is not a performance host.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
test -z "$(git status --porcelain --untracked-files=all)"
head="$(git rev-parse HEAD)"
tree="$(git rev-parse 'HEAD^{tree}')"
java -XshowSettings:properties -version 2>&1 | grep -Eq '^[[:space:]]*java.specification.version = 25$'
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
mvn -B -ntp -Dmaven.repo.local="$repo" -DskipTests install
mvn -B -ntp -Dmaven.repo.local="$repo" -f benchmarks/jmh/pom.xml package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
cp="$root/benchmarks/jmh/target/classes:$(cat benchmarks/jmh/target/classpath.txt)"
printf 'COMPILER_HEAD=%s COMPILER_TREE=%s\n' "$head" "$tree"
java -version
java -cp "$cp" org.openjdk.jmh.Main "$@"
test "$(git rev-parse HEAD)" = "$head"
test "$(git rev-parse 'HEAD^{tree}')" = "$tree"
test -z "$(git status --porcelain --untracked-files=all)"
