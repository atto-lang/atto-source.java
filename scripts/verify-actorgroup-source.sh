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

echo "ActorGroup source verification"
if command -v git >/dev/null 2>&1; then
  git rev-parse HEAD
  if [[ -n "$(git status --porcelain)" ]]; then
    echo "WARNING: working tree is modified; record the exact tested files" >&2
  fi
fi

# Fail if any specified regression class disappears or is renamed.
TESTS="SourceActorGroupExecutionTest,SourceActorSpawnExecutionTest,ActorGroup*Test,ActorEventBusTest,CooperativeActorGroupMailmanTest,ActorCapabilityIsolationTest,ActorTurnWatchdogTest,ActorTransportHardeningTest"
mvn -B -ntp '-P!native-thread-unix' \
  "-Dtest=${TESTS}" \
  -DfailIfNoTests=true test

if [[ "${ORESLANG_ACTORGROUP_FULL:-0}" == 1 ]]; then
  mvn -B -ntp '-P!native-thread-unix' test
fi
