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

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET

required = {
    "dev.oreslang.runtime.ScalarFrameLayoutTest": 6,
    "dev.oreslang.runtime.ActorScalarFrameStoreTest": 5,
    "dev.oreslang.runtime.ActorScalarFrameCodecTest": 3,
}
for name, expected in required.items():
    path = Path("target/surefire-reports") / ("TEST-" + name + ".xml")
    if not path.is_file() or not path.stat().st_size:
        raise SystemExit(f"FAIL missing nonempty test receipt: {path}")
    suite = ET.parse(path).getroot()
    counts = {field: int(suite.attrib.get(field, -1))
              for field in ("tests", "failures", "errors", "skipped")}
    if counts != {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}:
        raise SystemExit(f"FAIL unexpected receipt {path}: {counts}")
    print(f"FRAME_EXECUTED_SUITE {name} tests={expected}")
print("FRAME_HOST_RUNTIME_TESTS_PASS count=14")
PY
