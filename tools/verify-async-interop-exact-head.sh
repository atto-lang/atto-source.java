#!/usr/bin/env bash
# Source-org ONLY exact-head Java async interop proof.
# Run inside an isolated, pre-provisioned JDK25/GraalVM runner that has NO
# network route, no inherited GitHub credentials/secrets, and cached Maven deps.
# This script is NOT a private-source mirror exporter or sandbox boundary.
set -euo pipefail

die() { printf 'ASYNC_INTEROP_GATE_DENIED: %s\n' "$*" >&2; exit 1; }
[[ $# -eq 1 ]] || die 'usage: verify-async-interop-exact-head.sh FULL_40_HEX_PR_HEAD_SHA'
expected="$1"
[[ "$expected" =~ ^[0-9a-f]{40}$ ]] ||
  die 'expected exact lowercase 40-character commit SHA'
[[ "$(git rev-parse --is-inside-work-tree)" == true ]] ||
  die 'checkout is not a Git working tree'
[[ "$(git rev-parse HEAD)" == "$expected" ]] ||
  die 'working HEAD does not match frozen pull-request head'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] ||
  die 'dirty/untracked source tree; exact-head proof denied'
[[ "$(git rev-parse --show-toplevel)" == "$PWD" ]] ||
  die 'execute from the repository root'
[[ -f pom.xml ]] || die 'not the Java backend checkout'

# Refuse common credential injection into the untrusted build process.
for key in GITHUB_TOKEN GH_TOKEN GH_ENTERPRISE_TOKEN GIT_ASKPASS SSH_ASKPASS SSH_AUTH_SOCK \
           ORES_SOURCE_READ_TOKEN ORES_DEST_READ_TOKEN AWS_ACCESS_KEY_ID \
           AWS_SECRET_ACCESS_KEY AZURE_CLIENT_SECRET GOOGLE_APPLICATION_CREDENTIALS; do
  [[ -z "${!key:-}" ]] || die "credential-bearing environment variable present: $key"
done
command -v java >/dev/null 2>&1 || die 'Java missing'
command -v mvn >/dev/null 2>&1 || die 'Maven missing'
command -v python3 >/dev/null 2>&1 || die 'Python 3 missing'
java_major="$(java -XshowSettings:properties -version 2>&1 |
  sed -n 's/.*java.specification.version = \([0-9][0-9]*\).*/\1/p' |
  head -n 1)"
[[ "$java_major" == 25 ]] || die 'JDK 25 required, refusing other Java versions'
echo "ASYNC_INTEROP_HEAD=$expected"
echo "ASYNC_INTEROP_TREE=$(git rev-parse HEAD^{tree})"
echo 'ASYNC_INTEROP_SOURCE_ORG_ONLY=true'
echo 'ASYNC_INTEROP_EXPECTED_RUNNER_ISOLATION=preverified_external_control'

# Offline Maven still runs checked-out build scripts; admins MUST enforce
# egress denial and no-secret execution OUTSIDE this shell script.
# First verify the independent fail-closed JUnit receipt validator itself.
python3 -m unittest discover -s tests -p 'test_verify_async_interop_junit.py' -v

# Clean rebuild prevents stale class and JUnit artifacts from faking proof.
# Maven and its plugins/dependencies must already exist in an offline cache.
mvn -B -o -ntp \
  -Dores.runtime.carriers=native \
  -Dtest=OresFutureObserverTest,TaskFutureAdaptersTest,OneShotObservableTest,FutureFromInlineTaskLanguageTest \
  -DfailIfNoTests=true \
  -Dsurefire.failIfNoSpecifiedTests=true \
  -DskipTests=false -Dmaven.test.skip=false \
  clean test

# Never emit a proof marker merely because a Maven command returned zero.
# Require 42 distinct, actually-recorded non-skipped JUnit method results.
python3 tools/verify_async_interop_junit.py target/surefire-reports

# Revalidate the same source commit and clean tracked/untracked worktree after
# the build. A test that modifies input source/config cannot claim exact-head
# execution merely because the checkout was clean before running Maven.
[[ "$(git rev-parse HEAD)" == "$expected" ]] ||
  die 'source HEAD moved while focused tests ran'
[[ -z "$(git status --porcelain --untracked-files=all)" ]] ||
  die 'focused tests changed the source worktree; exact-head proof denied'

echo "ASYNC_INTEROP_JVM_PROOF_COMPLETE_HEAD=$expected"
echo 'ASYNC_INTEROP_JVM_PROOF_ONLY=true'
echo 'ASYNC_INTEROP_GRAAL_NATIVE_IMAGE_NOT_RUN=true'
