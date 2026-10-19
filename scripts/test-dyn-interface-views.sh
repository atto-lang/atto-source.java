#!/usr/bin/env bash
# Targeted dyn-interface contract test entrypoint.
# Run only in an authorized checkout or an approved exact-tree private CI carrier.
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if [[ ! -f pom.xml ]]; then
  echo "Missing pom.xml at verified source root" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1 || ! command -v mvn >/dev/null 2>&1; then
  echo "A Java runtime and Maven are required for executable dyn verification" >&2
  exit 2
fi

java -version
mvn -version

# All named tests must run; Surefire's -Dtest selection fails if no tests match.
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dtest=DynInterfaceViewTest,DynInheritedObjectSafetyTest,DynNominalWitnessTest,DynSendSyncConvergenceTest \
  test

# Optional full coverage for a funded, authorized worker.
if [[ "${ORES_DYN_FULL_VERIFY:-0}" == "1" ]]; then
  mvn -B -ntp -Dores.runtime.carriers=native verify
fi
