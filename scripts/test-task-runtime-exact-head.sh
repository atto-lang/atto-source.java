#!/usr/bin/env bash
# Exact-head Oreslang Task runtime verification.
# Run only in a private source-repository checkout on an approved runner.
set -euo pipefail

fail() {
  printf 'TASK_EXACT_HEAD_DENIED: %s\n' "$*" >&2
  exit 2
}

[[ $# -eq 1 && ( "$1" == "--focused" || "$1" == "--full" ) ]] ||
  fail "usage: $0 --focused|--full"

[[ -n "${ORES_TASK_EXPECTED_SHA:-}" ]] || fail "ORES_TASK_EXPECTED_SHA required"
[[ -n "${ORES_TASK_EXPECTED_TREE:-}" ]] || fail "ORES_TASK_EXPECTED_TREE required"
[[ "$ORES_TASK_EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "invalid commit SHA"
[[ "$ORES_TASK_EXPECTED_TREE" =~ ^[0-9a-f]{40}$ ]] || fail "invalid tree SHA"

if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  [[ "${GITHUB_REPOSITORY:-}" == "ores-truffle-oreslang/oreslang-source.java" ]] ||
    fail "wrong repository"
  [[ "${GITHUB_REF_PROTECTED:-}" == "true" ]] ||
    fail "unprotected ref"
  [[ "${GITHUB_SHA:-}" == "$ORES_TASK_EXPECTED_SHA" ]] ||
    fail "workflow event SHA differs from reviewed source SHA"
fi

actual_sha="$(git rev-parse --verify 'HEAD^{commit}')"
actual_tree="$(git rev-parse --verify 'HEAD^{tree}')"
[[ "$actual_sha" == "$ORES_TASK_EXPECTED_SHA" ]] ||
  fail "checkout commit mismatch"
[[ "$actual_tree" == "$ORES_TASK_EXPECTED_TREE" ]] ||
  fail "checkout tree mismatch"

if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  fail "checkout has tracked or untracked modifications"
fi

command -v mvn >/dev/null || fail "Maven missing"
command -v java >/dev/null || fail "Java missing"
command -v javac >/dev/null || fail "javac missing"
[[ "$(javac -version 2>&1)" == javac\ 25* ]] || fail "JDK 25 required"
java -version >&2

printf 'TASK_EXACT_HEAD_ADMITTED commit=%s tree=%s mode=%s\n' \
  "$actual_sha" "$actual_tree" "$1"

if [[ "$1" == "--focused" ]]; then
  mvn -B -ntp \
    -Dtest=OresTaskTest,OresTaskScopeTest,OresTaskScopeFrameTest,OresTaskCooperativeSchedulerTest,OresCancellationSourceTest \
    test
else
  mvn -B -ntp -Dores.runtime.carriers=native clean verify
fi

printf 'TASK_EXACT_HEAD_EXECUTED commit=%s tree=%s mode=%s\n' \
  "$actual_sha" "$actual_tree" "$1"
