#!/usr/bin/env bash
# Exact-source ActorGroup promotion gate. Run from this repository checkout.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required for the ActorGroup verification gate" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "A JDK >= 21 is required for the ActorGroup verification gate" >&2
  exit 2
fi

# A merge gate must prove the exact checked-out tree, not a partial or
# locally modified mirror. Callers may pin a PR HEAD with this environment
# variable; unexpected commits fail before touching the compiler.
if ! command -v git >/dev/null 2>&1 || ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ActorGroup promotion requires a Git checkout" >&2
  exit 2
fi
actual_sha="$(git rev-parse HEAD)"
echo "ActorGroup exact-source checkout: $actual_sha"
if [[ -n "${ORESLANG_ACTORGROUP_EXPECTED_SHA:-}" &&
      "$actual_sha" != "$ORESLANG_ACTORGROUP_EXPECTED_SHA" ]]; then
  echo "Wrong revision: expected $ORESLANG_ACTORGROUP_EXPECTED_SHA, got $actual_sha" >&2
  exit 2
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Dirty working tree: refusing to certify an uncommitted source state" >&2
  git status --short >&2
  exit 2
fi

# Fail closed if an explicitly required regression class is removed or renamed.
for required_test in \
  src/test/java/dev/oreslang/SourceActorGroupExecutionTest.java \
  src/test/java/dev/oreslang/SourceActorSpawnExecutionTest.java \
  src/test/java/dev/oreslang/runtime/ActorGroupMultiMembershipTest.java \
  src/test/java/dev/oreslang/runtime/ActorEventBusTest.java \
  src/test/java/dev/oreslang/runtime/CooperativeActorGroupMailmanTest.java \
  src/test/java/dev/oreslang/runtime/ActorCapabilityIsolationTest.java \
  src/test/java/dev/oreslang/runtime/ActorTurnWatchdogTest.java \
  src/test/java/dev/oreslang/ActorTransportHardeningTest.java; do
  if [[ ! -f "$required_test" ]]; then
    echo "Missing required regression: $required_test" >&2
    exit 2
  fi
done
TESTS="SourceActorGroupExecutionTest,SourceActorSpawnExecutionTest,ActorGroup*Test,ActorEventBusTest,CooperativeActorGroupMailmanTest,ActorCapabilityIsolationTest,ActorTurnWatchdogTest,ActorTransportHardeningTest"
mvn -B -ntp '-P!native-thread-unix' \
  "-Dtest=${TESTS}" \
  -DfailIfNoTests=true test

if [[ "${ORESLANG_ACTORGROUP_FULL:-0}" == 1 ]]; then
  mvn -B -ntp '-P!native-thread-unix' test
fi
