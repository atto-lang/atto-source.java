#!/usr/bin/env bash
set -euo pipefail

# Run ONLY from an authorized private source checkout, or a security-approved
# private exact-tree mirror. This script verifies content identity; it does NOT
# authorize source transfers or bypass the destination allowlist.
usage() {
  echo "usage: bash scripts/check-record-inference.sh [--commit|--tree] <40-hex-git-id>" >&2
  exit 2
}

mode="commit"
case "${1-}" in
  --commit) mode="commit"; shift ;;
  --tree) mode="tree"; shift ;;
  -*) usage ;;
esac
[[ "$#" == 1 ]] || usage
expected_id="$1"
[[ "$expected_id" =~ ^[0-9a-fA-F]{40}$ ]] || usage
expected_id="${expected_id,,}"

root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$root"
actual_root="$(git rev-parse --show-toplevel)"
if [[ "$actual_root" != "$root" ]]; then
  echo "refusing to test a different or nested Git checkout" >&2
  exit 3
fi
commit_sha="$(git rev-parse --verify HEAD^{commit})"
tree_sha="$(git rev-parse --verify HEAD^{tree})"
if [[ "$mode" == "commit" ]]; then
  actual_id="$commit_sha"
else
  actual_id="$tree_sha"
fi
if [[ "$actual_id" != "$expected_id" ]]; then
  echo "refusing to test wrong Git $mode: expected $expected_id, got $actual_id" >&2
  exit 3
fi

# Include ignored files. Maven or javac may compile ignored, untracked source
# files, and stale generated classes must not influence exact-tree results.
# Use a clean checkout; fail rather than deleting or silently trusting files.
if [[ -n "$(git status --porcelain=v1 --untracked-files=all --ignored)" ]]; then
  echo "refusing dirty or source-injected worktree (including ignored files)" >&2
  exit 4
fi
for program in javac java mvn git; do
  if ! command -v "$program" >/dev/null 2>&1; then
    echo "missing required test executable: $program" >&2
    exit 5
  fi
done

echo "Verified Oreslang source Git commit=$commit_sha tree=$tree_sha mode=$mode"
# For an approved mirror, the source commit may differ, but the full tree SHA
# must match the trusted source head. Modes/blobs are covered by Git tree IDs.
bash scripts/check-record-parser.sh
bash scripts/check-record-frontend.sh
bash scripts/check-soa-snapshot.sh

# Contains GraalVM guest execution, not only AST and host-only smoke tests.
mvn -B -ntp \
  -Dtest=InferredRecordBoundaryTest,InferredStructBoundaryTest,DynamicPropertyAccessBoundaryTest,DynamicPropertyAccessRuntimeTest,DynamicPropertyAccessRuntimeFailureTest,CheckedClassViewMetadataTest,PrecheckedProgramLeaseTest,TreeShakerTest,LinkedPublicClassFieldTypesTest,FileRootPublicClassImportTest,InferredRecordRuntimeTest,NamedInferredTypeTest,SoaSnapshotTest \
  test
mvn -B -ntp -Dores.runtime.carriers=native verify

echo "Oreslang record Maven verification PASS: commit=$commit_sha tree=$tree_sha"
# Native Image AOT/hybrid, LLVM, sanitizers and formatter parity require their
# own separately executed conformance jobs. No cross-org transfer is performed.
