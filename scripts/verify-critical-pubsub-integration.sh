#!/usr/bin/env bash
# Combined Java/GraalVM guest gate. A correct SHA and JUnit reports are
# necessary, not sufficient: provider job/runner/attempt must be read back.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ATTO_INTEGRATION_EXPECTED_SHA:?pin exact integration PR head}"
expected="$ATTO_INTEGRATION_EXPECTED_SHA"
[[ "$expected" =~ ^[0-9a-f]{40}$ ]] || { echo 'bad SHA' >&2; exit 2; }
actual="$(git rev-parse --verify HEAD)"
[[ "$actual" == "$expected" ]] || { echo 'wrong source checkout' >&2; exit 2; }
# This raw-tree verifier rejects smudge/clean substitutions and ignored
# untracked build inputs before Maven creates generated output.
bash scripts/test-raw-worktree.sh
bash scripts/verify-raw-worktree.sh "$expected"
test "$(git hash-object --no-filters contracts/v1/fixtures/actor-pubsub-formal-suspends-v1.atto)" = "fe10d9e5018a3f030a10bc31586e851198aaa4a3"
test "$(git hash-object --no-filters examples/critical-actor-checked-v1.atto)" = "50a62a32495989366624145c08c832e38c2c1a78"
cmp -s contracts/v1/fixtures/critical-pubsub-integration-v1.atto contracts/v1/fixtures/critical-pubsub-integration-v1.ores
test "$(git hash-object --no-filters contracts/v1/fixtures/critical-pubsub-integration-v1.atto)" = "9fe469af120fc948e1e68a55b783162c0b146105"
echo "INTEGRATION_SOURCE_HEAD=$expected TREE=$(git rev-parse 'HEAD^{tree}')"
# Keep the existing pub/sub exact-method and host adversarial suite.
ORESLANG_PUBSUB_EXPECTED_SHA="$expected" bash scripts/verify-actor-pubsub-v1.sh
# Then require actor-critical safety and the mixed guest program on the
# SAME source tree, not separate PR receipts.
mvn -B -ntp -Dores.runtime.carriers=java \
  "-Dtest=CriticalPubSubIntegrationGuestTest,CriticalActorGuestExecutionTest,CriticalSuspendsV1SyntaxTest,CriticalBodyEffectsTest,TaskCaptureAdmissionIdentityTest,CriticalTicketCleanupTest,CriticalGateTest,ActorCriticalGateIdentityTest" \
  -Dsurefire.failIfNoSpecifiedTests=true -DskipTests=false -Dmaven.test.skip=false test
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
required = {
    "dev.oreslang.CriticalPubSubIntegrationGuestTest": {
        "integrationSourceIsByteIdenticalAcrossAttoAndOres",
        "integrationReferencesPinnedCriticalAndPubsubSources",
        "combinedCriticalPubsubGuestRunsOnActualGraalRuntime",
        "sourceTaskTopicCapabilityIsRejectedAfterSuccessfulParsing",
        "forbiddenCriticalSuspendsModifierCombinationRemainsRejected",
    },
    "dev.oreslang.CriticalActorGuestExecutionTest": {
        "actorCriticalGuestReturnsExpectedValueAndReleasesBeforeStop",
        "earlyReturnInsideCriticalReleasesGuardBeforeActorStop",
        "canonicalAttoActorCriticalGuestMatchesLegacyGuestOutput",
        "canonicalAttoEarlyReturnGuestReleasesBeforeStop",
    },
    "dev.oreslang.CriticalSuspendsV1SyntaxTest": {
        "asyncIsNotAnAliasOfSuspends",
        "criticalAndSuspendsCannotBothModifyACallable",
        "criticalBlockHasDistinctAstAndRequiresActorOwnedGate",
    },
}
extra = [
    "dev.oreslang.CriticalBodyEffectsTest",
    "dev.oreslang.TaskCaptureAdmissionIdentityTest",
    "dev.oreslang.nodes.CriticalTicketCleanupTest",
    "dev.oreslang.runtime.CriticalGateTest",
    "dev.oreslang.runtime.ActorCriticalGateIdentityTest",
]
for suite in [*required, *extra]:
    path=Path('target/surefire-reports') / ('TEST-'+suite+'.xml')
    if not path.is_file():
        raise SystemExit(f'MISSING exact-head Surefire report: {path}')
    root=ET.parse(path).getroot()
    if int(root.attrib.get('tests', '0')) <= 0 or any(int(root.attrib.get(k,'0')) for k in ('failures','errors','skipped')):
        raise SystemExit(f'SUITE not fully executed and passing: {suite}')
    cases={case.attrib.get('name'): case for case in root.iter('testcase')}
    if not cases:
        raise SystemExit(f'EMPTY suite: {suite}')
    missing=required.get(suite,set())-cases.keys()
    if missing:
        raise SystemExit(f'MISSING concrete executed methods {suite}: {sorted(missing)}')
    for name in required.get(suite,()):
        case=cases[name]
        if any(case.find(x) is not None for x in ('failure','error','skipped')):
            raise SystemExit(f'NONPASS method {suite}.{name}')
    print(f'EXACT_METHOD_EXECUTED {suite} cases={len(cases)}')
PY
test -s target/classes/dev/oreslang/compiler/OresCompiler.class
sha256sum target/classes/dev/oreslang/compiler/OresCompiler.class
echo "INTEGRATION_EXACT_HEAD_PASS=$actual"
