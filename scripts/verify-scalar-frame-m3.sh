#!/usr/bin/env bash
# Exact-head, no-false-success scalar actor-frame acceptance. Run explicitly
# with bash; GitHub's contents API does not set executable file mode.
set -euo pipefail

: "${EXPECTED_HEAD:?Pass reviewed full Git commit SHA as EXPECTED_HEAD}"
: "${EXPECTED_TREE:?Pass reviewed full Git tree SHA as EXPECTED_TREE}"
actual_head="$(git rev-parse HEAD)"
actual_tree="$(git rev-parse 'HEAD^{tree}')"
if [[ "$actual_head" != "$EXPECTED_HEAD" || "$actual_tree" != "$EXPECTED_TREE" ]]; then
  printf 'FAIL exact Git identity expected %s/%s got %s/%s\n' \
    "$EXPECTED_HEAD" "$EXPECTED_TREE" "$actual_head" "$actual_tree" >&2
  exit 90
fi

printf 'AttoLang scalar M3 exact head %s tree %s\n' "$actual_head" "$actual_tree"
java -version
mvn -version
for scenario in deep-non-tail-scalar-frame mutual-tail-scalar-frame immutable-local-scalar-frame; do
  path="src/test/resources/actor-stack/${scenario}.atto"
  test -s "$path" || { echo "missing source: $path" >&2; exit 91; }
  printf 'fixture %s blob ' "$path"
  git hash-object "$path"
done

# Do not swallow failures through pipes or claim success from a 0-step job.
mvn -B -ntp \
  -Dtest=ScalarFrameMachineTest,ScalarFrameStorageTest,ScalarFrameLanguageTest,ActorSourceTaskFrameTest,GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,GuestCallDepthGuardTest,ProperTailCallTest \
  test

python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET

folder = Path("target/surefire-reports")
reports = sorted(folder.glob("TEST-*.xml"))
if not reports:
    raise SystemExit("FAIL no executed JUnit XML receipts")
total = failed = 0
classes = set()
for path in reports:
    suite = ET.parse(path).getroot()
    total += int(suite.attrib.get("tests", 0))
    failed += int(suite.attrib.get("failures", 0))
    failed += int(suite.attrib.get("errors", 0))
    for case in suite.iter("testcase"):
        classes.add(case.attrib.get("classname", "").split(".")[-1])
required = {"ScalarFrameMachineTest", "ScalarFrameStorageTest", "ScalarFrameLanguageTest"}
missing = required - classes
if total <= 0 or failed or missing:
    raise SystemExit(f"FAIL JUnit receipt tests={total} failures={failed} missing={sorted(missing)}")
print(f"HOST+COMPILER-TEST SUITES EXECUTED {total} methods, failures=0; key suites={sorted(required)}")
print("IMPORTANT: hosted CI step counts and native stack parity require separate workflow receipts")
PY
