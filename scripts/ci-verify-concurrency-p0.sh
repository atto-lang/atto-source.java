#!/usr/bin/env bash
set -euo pipefail

: "${EXPECTED_SOURCE_TREE_SHA:?Set EXPECTED_SOURCE_TREE_SHA to the exact private source Git tree SHA}"
if [[ ! ${EXPECTED_SOURCE_TREE_SHA} =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid source Git tree SHA" >&2
  exit 2
fi
actual="$(git rev-parse 'HEAD^{tree}')"
if [[ "${actual}" != "${EXPECTED_SOURCE_TREE_SHA}" ]]; then
  echo "MISMATCH: checked-out Git tree does not match source PR" >&2
  exit 2
fi
git diff --quiet HEAD -- || { echo "Tracked files modified before test" >&2; exit 2; }
if [[ -n "$(git ls-files --others --exclude-standard)" ]]; then
  echo "Untracked checkout files present before exact-tree test; refusing contaminated inputs" >&2
  exit 2
fi
command -v mvn >/dev/null || { echo "Maven unavailable" >&2; exit 2; }
java -version >&2

# The Maven build uses the real compiled sources and test classes, not
# a reconstructed fixture. Failures must prevent the receipt from printing.
mvn -B -Dtest=ConcurrencyRaceAnalyzerTest,ConcurrencyTestProbeTest,ConcurrencySourceFingerprintTest,ConcurrencyTestCommandTest test
if [[ "${RUN_FULL_SUITE:-0}" == "1" ]]; then
  mvn -B test
fi
git diff --quiet HEAD -- || { echo "Tracked files modified during test" >&2; exit 2; }
echo "ORES_CONCURRENCY_CI|backend=graal|source_tree=${actual}|result=PASS|scope=targeted"
