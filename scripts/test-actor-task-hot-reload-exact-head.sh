#!/usr/bin/env bash
# Execute ONLY in an authorized private source-repository checkout/runner.
# Does not export source or clone it into an unrelated GitHub organization.
set -euo pipefail

deny() { printf 'ACTOR_TASK_EXACT_HEAD_DENIED: %s\n' "$*" >&2; exit 2; }

[[ "${ORES_HOT_TASK_EXPECTED_SHA:-}" =~ ^[0-9a-f]{40}$ ]] ||
  deny "reviewed SHA must be supplied"
[[ "${ORES_HOT_TASK_EXPECTED_TREE:-}" =~ ^[0-9a-f]{40}$ ]] ||
  deny "reviewed tree must be supplied"
[[ "$(git rev-parse --show-toplevel)" == "$PWD" ]] ||
  deny "run from the exact checkout root"

if [[ "${GITHUB_ACTIONS:-}" == true ]]; then
  [[ "${GITHUB_REPOSITORY:-}" == 'ores-truffle-oreslang/oreslang-source.java' ]] ||
    deny "wrong GitHub source repository"
  [[ "${GITHUB_REF_PROTECTED:-}" == true ]] ||
    deny "unreviewed unprotected workflow ref"
  [[ "${GITHUB_SHA:-}" == "$ORES_HOT_TASK_EXPECTED_SHA" ]] ||
    deny "workflow event SHA mismatch"
fi

actual_sha="$(git rev-parse 'HEAD^{commit}')"
actual_tree="$(git rev-parse 'HEAD^{tree}')"
[[ "$actual_sha" == "$ORES_HOT_TASK_EXPECTED_SHA" ]] ||
  deny "commit does not match reviewed immutable head"
[[ "$actual_tree" == "$ORES_HOT_TASK_EXPECTED_TREE" ]] ||
  deny "tracked Git tree does not match reviewed immutable tree"
[[ -z "$(git status --porcelain=v1 --untracked-files=all)" ]] ||
  deny "dirty tracked/untracked workspace"
[[ -z "$(git ls-files --others --ignored --exclude-standard)" ]] ||
  deny "ignored/untracked files could contaminate compilation"

for parent in \
  '7424d1547b16d8d9f0639c625eb3954e0b50469e' \
  'f589c6a42c20fd0606f66aa94bc28f4ed6666111'; do
  git merge-base --is-ancestor "$parent" HEAD ||
    deny "reviewed parent $parent not present in commit history"
done

# A full HEAD/tree digest is authoritative. These pinned blobs additionally
# show exactly WHICH reviewed hot/Task safety seams are present in that tree.
reviewed_blobs=(
  'src/main/java/dev/oreslang/runtime/ActorUpgradeAtomicPublicationV1.java:c5b5c5bfcba246fba9a65574a4e961c2b2733d8d'
  'src/main/java/dev/oreslang/runtime/LogicalActorProxyMailboxV1.java:f3182684b3af0101f6f218b8f9306ac819d0ebae'
  'src/main/java/dev/oreslang/runtime/LogicalSourceActorRouterV1.java:11362a6706729f6f903addc66e83cd82e2e2a853'
  'src/main/java/dev/oreslang/runtime/ActorUpgradeEpochGate.java:0c0315f643fc25b3335e77e64375666cbc2e6d05'
  'src/main/java/dev/oreslang/runtime/OresTaskScope.java:6220209e55dc43311e7d2ead98225e916ee9540e'
  'src/main/java/dev/oreslang/runtime/OresContext.java:c0e8d02cacb036c8570cb4bb5a6c3967ab6030b8'
  'src/main/java/dev/oreslang/runtime/ActorRoutePublicationV1.java:399268990b952ef7399d7787c8738901dbcd73d2'
  'src/main/java/dev/oreslang/runtime/ActorUpgradePreflightV1.java:74f8bc8070449f9448997c8d3b714ddfd58e4a00'
)
for record in "${reviewed_blobs[@]}"; do
  path="${record%%:*}"
  expected="${record#*:}"
  [[ "$(git rev-parse "HEAD:$path")" == "$expected" ]] ||
    deny "reviewed source blob mismatch: $path"
done

command -v mvn >/dev/null || deny "Maven not installed"
command -v javac >/dev/null || deny "JDK not installed"
[[ "$(javac -version 2>&1)" == javac\ 25* ]] ||
  deny "GraalVM/JDK 25 compilation required"
test_classes=(
  ActorUpgradeEpochGateTest
  ActorHotReloadPlacementAndTaskPinTest
  ActorTaskGenerationBridgeTest
  ActorUpgradePreflightV1Test
  ActorRoutePublicationV1Test
  ActorUpgradeAtomicPublicationV1Test
  LogicalActorProxyMailboxV1Test
  OresTaskScopeFrameTest
  SourceActorHotUpgradeExecutionTest
  LogicalSourceActorCrossContextUpgradeTest
)
selection="$(IFS=,; echo "${test_classes[*]}")"

mvn -B -ntp -DfailIfNoTests=true "-Dtest=$selection" test

# Require the exact requested JUnit suites actually executed. This prevents
# report-less/zero-step Actions failures from masquerading as test success.
python3 - "${test_classes[@]}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

verified = 0
for name in sys.argv[1:]:
    reports = list(Path('target/surefire-reports').glob(f'TEST-*.{name}.xml'))
    if len(reports) != 1:
        raise SystemExit(f'Missing/ambiguous JUnit receipt for {name}')
    root = ET.parse(reports[0]).getroot()
    counts = {key: int(root.get(key, '0'))
              for key in ('tests', 'failures', 'errors', 'skipped')}
    if counts['tests'] < 1 or any(counts[k] for k in ('failures', 'errors', 'skipped')):
        raise SystemExit(f'Failing, skipped or empty JUnit {name}: {counts}')
    verified += counts['tests']
print(f'ACTOR_TASK_HOST_AND_SHARED_JUNIT_EXECUTED tests={verified}')
PY

printf 'ACTOR_TASK_EXACT_HEAD_EXECUTED sha=%s tree=%s\n' "$actual_sha" "$actual_tree"
