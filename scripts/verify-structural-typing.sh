#!/usr/bin/env bash
# Run from the repository root: bash scripts/verify-structural-typing.sh
# Uses the exact checked-out source tree. Requires Maven and a compatible
# GraalVM JDK (the CI reference uses GraalVM 25.3/JDK 25).
set -euo pipefail

cd "$(dirname "$0")/.."
echo "Oreslang structural typing verification"
echo "Commit: $(git rev-parse HEAD)"
java -version
mvn -version

tests=(
  InlineStructuralRecordContractTest
  StructuralTypingHardeningTest
  IntersectionTypeHardeningTest
  StructuralReadViewCoercionHardeningTest
  StructuralOwnershipBoundaryIntegrationTest
  StructuralLoanLaunderingRegressionTest
  ActorStructuralRecordTransportTest
  LinkedActorCompilerAdmissionTest
  CrossFileActorCapabilityHardeningTest
  SourceActorSpawnExecutionTest
  ActorTransportHardeningTest
  InferredStructReadonlyBoundaryTest
  OwnershipAndClosureTest
)
test_filter=$(IFS=,; echo "${tests[*]}")

echo "== Targeted parser/types/ownership/actor transport tests =="
mvn -B -ntp -Dores.runtime.carriers=native "-Dtest=${test_filter}" test

echo "== Full source-tree verification =="
mvn -B -ntp -Dores.runtime.carriers=native verify

echo "== Verified exact source commit: $(git rev-parse HEAD) =="
