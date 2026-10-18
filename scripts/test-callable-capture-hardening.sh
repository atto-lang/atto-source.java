#!/usr/bin/env bash
set -euo pipefail

# Exact-head, local-safe validation: no remote checkout, secrets, or CI mirroring.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
bash scripts/test-anycallable-standalone.sh
bash scripts/test-lambda-capture-standalone.sh

# Keep the ownership scanner, closure projection and runtime environments
# in the same release gate. Execute with GraalVM JDK 25 when available.
mvn -B -ntp \
  -Dtest=AnyCallableActorBoundaryTest,CallableSemanticsTest,LambdaCapturePlannerTest,LambdaClosureEnvironmentIsolationTest,OwnershipMatchExpressionCaptureTest \
  test

if [[ "${ORESLANG_FULL_VERIFY:-0}" == "1" ]]; then
  mvn -B -ntp -Dores.runtime.carriers=native verify
fi
