#!/usr/bin/env bash
# Exact-tree evidence gate for the ObservableActor Java runtime slice.
# Can run on a private, locally mirrored carrier. Never fetches source with
# a cross-org token, and never claims Native Image or guest-language parity.
set -euo pipefail

: "${EXPECTED_SOURCE_TREE:?Set the complete source Git tree SHA}"
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

# A private carrier keeps the Oreslang source in ./source so its own
# workflows run without executing the mirrored source workflows.
# Only the known root and nested layouts are admitted.
source_path="${SOURCE_SUBTREE_PATH:-.}"
case "$source_path" in
  .) actual_tree="$(git rev-parse --verify 'HEAD^{tree}')" ;;
  source) actual_tree="$(git rev-parse --verify 'HEAD:source')" ;;
  *) echo "FAIL: unsupported source subtree path" >&2; exit 47 ;;
esac
if [[ "$(git cat-file -t "$actual_tree")" != "tree" ]]; then
  echo "FAIL: checked-out source path is not a Git tree" >&2
  exit 47
fi
if [[ "$actual_tree" != "$EXPECTED_SOURCE_TREE" ]]; then
  echo "FAIL: source-tree mismatch expected=$EXPECTED_SOURCE_TREE actual=$actual_tree" >&2
  exit 40
fi

if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo "FAIL: unclean carrier checkout; source-tree proof invalid" >&2
  exit 41
fi
cd "$source_path"

java_version="$(java -version 2>&1)"
if ! grep -Eqi 'GraalVM' <<<"$java_version"; then
  echo "FAIL: GraalVM JDK 25 required (not generic JDK)" >&2
  exit 42
fi
if ! grep -Eq 'version "25|version 25' <<<"$java_version"; then
  echo "FAIL: Java major version 25 required" >&2
  exit 43
fi

suites=(ActorPublisherScopedObservationTest ActorReliableSequenceAdmissionTest ActorEventBusTest)
mvn -B -ntp -Dtest="$(IFS=,; echo "${suites[*]}")" -DfailIfNoTests=true clean test

for suite in "${suites[@]}"; do
  report="target/surefire-reports/TEST-dev.oreslang.runtime.${suite}.xml"
  if [[ ! -s "$report" ]]; then
    echo "FAIL: missing Surefire report $report" >&2
    exit 44
  fi
  if ! grep -Eq 'tests="[1-9][0-9]*"' "$report"; then
    echo "FAIL: no executed tests for $suite" >&2
    exit 45
  fi
  if grep -Eq '(failures|errors|skipped)="[1-9][0-9]*"' "$report"; then
    echo "FAIL: failed, errored or skipped tests in $suite" >&2
    exit 46
  fi
done

echo "OBSERVABLE_ACTOR_EXACT_TREE_JVM_SUCCEEDED=$actual_tree"
echo "OBSERVABLE_ACTOR_JVM_SUITES=3"
echo "OBSERVABLE_ACTOR_NATIVE_IMAGE_CERTIFIED=false"
echo "OBSERVABLE_ACTOR_GUEST_SOURCE_CERTIFIED=false"
