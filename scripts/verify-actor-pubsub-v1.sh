#!/usr/bin/env bash
# Fail closed unless the exact Oreslang/AttoLang actor pub/sub source head
# compiled and executed its focused Java/GraalVM and guest .ores corpus.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ORESLANG_PUBSUB_EXPECTED_SHA:?pin the 40-character PR head SHA}"
if [[ ! "$ORESLANG_PUBSUB_EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Expected SHA must be a full 40-character lowercase Git commit" >&2
  exit 2
fi
actual="$(git rev-parse --verify HEAD)"
if [[ "$actual" != "$ORESLANG_PUBSUB_EXPECTED_SHA" ]]; then
  echo "Wrong checkout: expected=$ORESLANG_PUBSUB_EXPECTED_SHA actual=$actual" >&2
  exit 2
fi
# Git status/diff may invoke a configured clean filter before test admission.
# Check the index tree and physical raw bytes instead, with no filter execution.
command -v python3 >/dev/null || { echo "Python 3 unavailable" >&2; exit 2; }
python3 scripts/verify-raw-worktree.py --self-test
python3 scripts/verify-raw-worktree.py .

command -v mvn >/dev/null || { echo "Maven unavailable" >&2; exit 2; }

# Pin both newly added share! source fixtures before Maven/guest execution.
test "$(git hash-object --no-filters contracts/v1/fixtures/actor-pubsub-share-bang-v1.atto)" = "00f311e2f569cf66009ba533282b63d35571853f"
test "$(git hash-object --no-filters contracts/v1/fixtures/actor-pubsub-reject-unlowered-share-bang-try-publish.atto)" = "dc33b5970301f4fbb84eea001e19b9611c0a0586"
test "$(git hash-object --no-filters contracts/v1/fixtures/actor-pubsub-try-publish-v1.atto)" = "ed35171946748eed4f970442d5cdb2c61c96029d"
test "$(git hash-object --no-filters contracts/v1/fixtures/actor-pubsub-try-publish-policy-denied.atto)" = "5f3d79ba763a98ff6a1daf5b85d9d6ae0cec6a41"

# Must execute the Channel<T> recursive Sendable/callable negatives,
# Must execute the guest-positive fixtures and all guest compile negatives,
# including direct/nested callable topic payloads, not only host facade tests. Surefire's -Dtest does not permit matching
# empty suites; we additionally inspect the exact suite reports below.
suites=(
  dev.oreslang.SourceActorPubSubExecutionTest
  dev.oreslang.CanonicalSuspendsPubSubGuestTest
  dev.oreslang.ChannelCallableTransportTest
  dev.oreslang.runtime.ActorPubSubV1ContractTest
  dev.oreslang.runtime.ActorPubSubConcurrencyTest
  dev.oreslang.runtime.ActorPubSubAdversarialAdmissionTest
  dev.oreslang.runtime.SourceActorPubSubTest
)
# Maven's selection is explicit, while reports are checked by full class name.
selection="SourceActorPubSubExecutionTest,CanonicalSuspendsPubSubGuestTest,ChannelCallableTransportTest,ActorPubSubV1ContractTest,ActorPubSubConcurrencyTest,ActorPubSubAdversarialAdmissionTest,SourceActorPubSubTest"
mvn -B -ntp -Dores.runtime.carriers=native \
  "-Dtest=$selection" -Dsurefire.failIfNoSpecifiedTests=true test

total=0
for suite in "${suites[@]}"; do
  report="target/surefire-reports/TEST-${suite}.xml"
  if [[ ! -s "$report" ]]; then
    echo "No executed Surefire report for $suite" >&2
    exit 2
  fi
  executed="$(grep -c '<testcase' "$report" || true)"
  if [[ "$executed" -lt 1 ]]; then
    echo "Suite has zero executed tests: $suite" >&2
    exit 2
  fi
  if grep -Eq '(errors|failures|skipped)="[1-9][0-9]*"|tests="0"' "$report"; then
    echo "Suite has failures, skips or zero admitted tests: $suite" >&2
    exit 2
  fi
  total=$((total + executed))
  echo "EXECUTED $suite: $executed tests"
done
# The generic suite-count gate is insufficient: one unrelated test could
# satisfy it while the new source-guest fixture or security negatives never ran.
# Require concrete Surefire method names from the exact-head XML documents.
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET

expected = {
    "dev.oreslang.CanonicalSuspendsPubSubGuestTest": {
        "canonicalSuspendsSourceMatchesPinnedFormalFixture",
        "canonicalSuspendsFncExecutesThroughGraalPolyglot",
    },
    "dev.oreslang.SourceActorPubSubExecutionTest": {
        "positivePubSubFixturesUseSuspendsOnlyEntrypoints",
        "suspendsTopicCapabilityFailsTaskSendBoundaryAfterParserAdmission",
        "suspendsTaskBoundaryRejectsOtherAndNestedNonSendCapabilities",
        "suspendsTaskReturnCannotExportActorOwnedTopic",
        "suspendsActorReceiveRetainsActorLocalTopicAndSubscriptionUse",
        "sharedActorSubscribesAndReceivesTypedInMemoryPublication",
        "attolangFilenameExecutesExistingPubSubGuest",
        "pubSubHandlesCannotCrossIndependentSuspendsTaskBoundary",
        "legacyAsyncGuestFanoutRunsOnTwoIndependentSharedActors",
        "portableTryPublishRejectsMissingShareProof",
        "canonicalShareBangUsesSameOwnershipAstInsidePortableTryPublish",
        "canonicalShareBangTypeChecksWithExistingAsyncTopicPublish",
        "shareBangAttoGuestPublishesCheckedScalarOnExistingAsyncTopic",
        "shareBangTryPublishRejectsMismatchedPayloadType",
        "shareBangTypedTryPublishExecutesGuestWithUnsignedReceipt",
        "directTryPublishCallWithoutShareProofCannotCompile",
        "typedTryPublishRefusesLossyPolicyInActualGuest",
        "attoMultiGroupGuestPreservesOtherGroupAfterLeave",
        "attoPubSubTypeAndAuthorityNegativesStayFailClosed",
    },
    "dev.oreslang.runtime.ActorPubSubV1ContractTest": {
        "acceptedPublicationInvokesReductionCommitExactlyOncePerPolicy",
        "failedReliableAdmissionCannotCommitReductionOrConsumeSequence",
        "admissionReceiptPreservesLegacyConstructorAndUnsignedSequenceText",
    },
    "dev.oreslang.runtime.ActorPubSubAdversarialAdmissionTest": {
        "publisherScopedSubscriptionsShareTheCrossTopicActorQuota",
        "retiredPublisherRevokesItsScopedSubscriptionWithoutRevokingObserver",
        "zeroRecipientPublicationsAllocateSequenceButDoNotReplay",
        "reliableRejectionRollsBackEverySubscriberReservation",
        "tryReliableRejectsExtensionPoliciesAndInvalidPayloadWithoutCreatingReceipts",
        "typedTryReliableRefusesMissingActorAuthorityAndAdmitsAfterJoin",
    },
}
for suite, required_methods in expected.items():
    report = Path("target/surefire-reports") / f"TEST-{suite}.xml"
    root = ET.parse(report).getroot()
    cases = {
        case.get("name"): case
        for case in root.iter("testcase")
    }
    missing = required_methods - cases.keys()
    if missing:
        raise SystemExit(
            f"Required pub/sub test methods NOT_EXECUTED in {suite}: {sorted(missing)}")
    for method in required_methods:
        case = cases[method]
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise SystemExit(f"Required pub/sub test did not PASS: {suite}.{method}")
    print(f"EXACT_METHOD_EVIDENCE {suite}: {', '.join(sorted(required_methods))}")
PY

# The fixture is not a portable try_publish implementation. Preserve its
# exact on-disk hash alongside the executed JUnit methods in CI logs.
sha256sum contracts/v1/fixtures/actor-pubsub-legacy-fanout-two.atto
sha256sum contracts/v1/fixtures/actor-pubsub-formal-suspends-v1.atto
echo "ACTOR_PUBSUB_EXACT_HEAD_PASS sha=$actual executed_tests=$total"
