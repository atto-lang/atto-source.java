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

# Git status/diff trust the index. An attacker can set skip-worktree or
# assume-unchanged and alter a tracked source file without either command
# noticing. Reject every non-normal entry, including unmerged stages, using
# NUL-delimited names so embedded whitespace cannot hide a flagged path.
verify_normal_index_flags() {
  local entry
  while IFS= read -r -d '' entry; do
    if [[ "${entry:0:2}" != "H " ]]; then
      echo "refusing hidden or unresolved Git index entry (tag ${entry:0:1})" >&2
      return 1
    fi
  done < <(git ls-files -v -z)
}

if ! verify_normal_index_flags; then
  echo "refusing pre-admission hidden Git index state" >&2
  exit 4
fi

# The entry check rejects all ignored/untracked files. Subsequent Java/Maven
# commands can legitimately generate ignored build products, but MUST NOT
# mutate the checked-out source, switch HEAD, or inject unignored files.
# Check after every stage so a source mutation cannot affect the next stage.
verify_inputs_unchanged() {
  local stage="$1"
  if [[ "$(git rev-parse --show-toplevel)" != "$root" ]] \
      || [[ "$(git rev-parse --verify HEAD^{commit})" != "$commit_sha" ]] \
      || [[ "$(git rev-parse --verify HEAD^{tree})" != "$tree_sha" ]] \
      || ! verify_normal_index_flags \
      || ! git diff --quiet HEAD -- \
      || [[ -n "$(git ls-files --others --exclude-standard)" ]] \
      || [[ -n "$(git ls-files --others --ignored --exclude-standard -- src stdlib scripts examples .mvn .github)" ]]; then
    echo "refusing changed Git/source inputs after $stage" >&2
    exit 6
  fi
}

echo "Verified Oreslang source Git commit=$commit_sha tree=$tree_sha mode=$mode"
# For an approved mirror, the source commit may differ, but the full tree SHA
# must match the trusted source head. Modes/blobs are covered by Git tree IDs.
bash scripts/check-record-parser.sh
verify_inputs_unchanged "record parser"
bash scripts/check-record-frontend.sh
verify_inputs_unchanged "record frontend"
bash scripts/check-soa-snapshot.sh
verify_inputs_unchanged "SoA snapshot"

# Contains GraalVM guest execution, not only AST and host-only smoke tests.
mvn -B -ntp \
  -Dtest=RecordTypeValueContextTest,InferredRecordBoundaryTest,InferredStructBoundaryTest,DynamicPropertyAccessBoundaryTest,DynamicPropertyAccessRuntimeTest,DynamicPropertyAccessRuntimeFailureTest,CheckedClassViewMetadataTest,PrecheckedProgramLeaseTest,TreeShakerTest,LinkedPublicClassFieldTypesTest,FileRootPublicClassImportTest,InferredRecordRuntimeTest,NamedInferredTypeTest,SoaSnapshotTest \
  test
verify_inputs_unchanged "targeted record JUnit"
mvn -B -ntp -Dores.runtime.carriers=native verify
verify_inputs_unchanged "native carrier Maven verify"

echo "Oreslang record Maven verification PASS: commit=$commit_sha tree=$tree_sha"
# Native Image AOT/hybrid, LLVM, sanitizers and formatter parity require their
# own separately executed conformance jobs. No cross-org transfer is performed.
