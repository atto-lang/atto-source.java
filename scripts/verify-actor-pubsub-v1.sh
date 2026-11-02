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
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Refusing to certify a dirty checkout" >&2
  git status --short >&2
  exit 2
fi

command -v mvn >/dev/null || { echo "Maven unavailable" >&2; exit 2; }

# Must execute the Channel<T> recursive Sendable/callable negatives,
# Must execute the guest-positive fixtures and all guest compile negatives,
# including direct/nested callable topic payloads, not only host facade tests. Surefire's -Dtest does not permit matching
# empty suites; we additionally inspect the exact suite reports below.
suites=(
  dev.oreslang.SourceActorPubSubExecutionTest
  dev.oreslang.ChannelCallableTransportTest
  dev.oreslang.runtime.ActorPubSubV1ContractTest
  dev.oreslang.runtime.ActorPubSubConcurrencyTest
  dev.oreslang.runtime.ActorPubSubAdversarialAdmissionTest
  dev.oreslang.runtime.SourceActorPubSubTest
)
# Maven's selection is explicit, while reports are checked by full class name.
selection="SourceActorPubSubExecutionTest,ChannelCallableTransportTest,ActorPubSubV1ContractTest,ActorPubSubConcurrencyTest,ActorPubSubAdversarialAdmissionTest,SourceActorPubSubTest"
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
echo "ACTOR_PUBSUB_EXACT_HEAD_PASS sha=$actual executed_tests=$total"
