#!/usr/bin/env bash
# Exact-source admission for the Java physical-turn scalar-frame staging slice.
# Do not count a workflow without executed build/test steps as a test pass.
set -euo pipefail

: "${EXPECTED_HEAD:?Supply the reviewed full Git commit SHA}"
: "${EXPECTED_TREE:?Supply the reviewed full Git tree SHA}"
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
test "$(git rev-parse 'HEAD^{tree}')" = "$EXPECTED_TREE"
git diff --quiet
git diff --cached --quiet
test -z "$(git status --porcelain)"

for file in \
  scripts/VerifyActorScalarFrameReports.java \
  src/main/java/dev/oreslang/runtime/ScalarFrameLayout.java \
  src/main/java/dev/oreslang/runtime/ActorScalarFrameStore.java \
  src/test/java/dev/oreslang/runtime/ScalarFrameLayoutTest.java \
  src/test/java/dev/oreslang/runtime/ActorScalarFrameStoreTest.java \
  src/test/java/dev/oreslang/runtime/ActorScalarFrameCodecTest.java; do
  git ls-files --error-unmatch -- "$file" >/dev/null
  echo "FRAME_EXACT_BLOB $file $(git hash-object "$file")"
done

java -version
mvn -version
# Fail closed against stale reports from a prior commit/build invocation.
rm -rf target/surefire-reports
mvn -B -ntp -DfailIfNoTests=true \
  -Dtest=ScalarFrameLayoutTest,ActorScalarFrameStoreTest,ActorScalarFrameCodecTest test

# JDK source-file mode: no Python toolchain or external XML parser.
java scripts/VerifyActorScalarFrameReports.java
