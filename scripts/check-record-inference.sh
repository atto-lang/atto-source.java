#!/usr/bin/env bash
set -euo pipefail

# Execute ONLY on an authorized private source repository checkout or an
# explicitly approved, complete-tree private mirror. Never use an unapproved
# repo, public mirror, or secrets-bearing workflow to work around CI minutes.
expected_sha="${1:?usage: bash scripts/check-record-inference.sh <exact-40-character-commit-sha>}"
if [[ ! "$expected_sha" =~ ^[[:xdigit:]]{40}$ ]]; then
  echo "expected SHA must have exactly 40 hexadecimal digits" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
actual_sha="$(git rev-parse --verify HEAD)"
if [[ "$actual_sha" != "$expected_sha" ]]; then
  echo "refusing to run tests on wrong source: expected $expected_sha, got $actual_sha" >&2
  exit 3
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "refusing to test modified tracked files; use an exact clean Git tree" >&2
  exit 4
fi
for program in javac java mvn git; do
  if ! command -v "$program" >/dev/null 2>&1; then
    echo "missing required test executable: $program" >&2
    exit 5
  fi
done

echo "Exact-head Oreslang record verification: $actual_sha"
bash scripts/check-soa-snapshot.sh

# The targeted suite includes a *real GraalVM guest execution*, not just
# AST-parser or standalone host-side SoA tests.
mvn -B -ntp \
  -Dtest=InferredRecordBoundaryTest,InferredRecordRuntimeTest,NamedInferredTypeTest,SoaSnapshotTest \
  test
mvn -B -ntp -Dores.runtime.carriers=native verify

echo "Exact-head record inference Maven verification PASS: $actual_sha"
# Native Image AOT/hybrid, LLVM, sanitizers and formatter parity are separate
# release gates; this script deliberately makes no claims about those targets.
