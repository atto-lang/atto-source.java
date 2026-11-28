#!/usr/bin/env bash
# Preserve every test, including repetitions; isolate hangs in fresh JVMs.
set -euo pipefail
ulimit -c 0
root="$(git rev-parse --show-toplevel)"
cd "$root"
here="$root/tests/fixtures/sharkbench-admission"
out="$root/target/sharkbench-native-http"
mkdir -p "$out"
python3 - <<'PY' > "$out/inventory"
import pathlib, re
src = pathlib.Path("src/test/java/dev/oreslang/runtime/NativeHttpTest.java").read_text()
annotation = r"@Test\b|@org\.junit\.jupiter\.api\.RepeatedTest\(\d+\)"
pattern = r"(?m)^\s*(?:@Test\b|@org\.junit\.jupiter\.api\.RepeatedTest\((\d+)\))\s*(?:@org\.junit\.jupiter\.api\.Timeout\(\d+\)\s*)?void\s+(\w+)\s*\("
cases = re.findall(pattern, src)
if not cases or len(cases) != len(re.findall(annotation, src)) or len({name for _, name in cases}) != len(cases):
    raise SystemExit("NativeHttpTest inventory incomplete or ambiguous")
for repeats, name in cases:
    print(name, int(repeats or 1))
PY
cat "$out/inventory"
bash "$here/maven-retry.sh" -B -ntp test-compile
failed=0
while read -r method count; do
    echo "BEGIN NativeHttpTest#$method expected=$count"
    report="$root/target/surefire-reports/TEST-dev.oreslang.runtime.NativeHttpTest.xml"
    rm -f "$report"
    limit=65
    if (( count > 1 )); then limit=165; fi
    rc=0
    # SIGQUIT requests JVM thread diagnostics before forced process-group cleanup.
    timeout --signal=QUIT --kill-after=8s "${limit}s" bash "$here/maven-retry.sh" -B -ntp \
        "-Dtest=NativeHttpTest#$method" -Dsurefire.failIfNoSpecifiedTests=true \
        -DfailIfNoTests=true -Djunit.jupiter.execution.timeout.default=45s \
        -Djunit.jupiter.execution.timeout.threaddump.enabled=true surefire:test \
        > "$out/$method.log" 2>&1 || rc=$?
    if [[ -f "$report" ]]; then cp "$report" "$out/$method.xml"; fi
    if (( rc == 0 )); then
        python3 - "$report" "$count" <<'PY' || rc=$?
import sys, xml.etree.ElementTree as ET
suite = ET.parse(sys.argv[1]).getroot()
if int(suite.attrib["tests"]) != int(sys.argv[2]) or any(int(suite.attrib.get(key, 0)) for key in ("failures","errors","skipped")):
    raise SystemExit("missing, failed or skipped case")
PY
    fi
    printf 'NativeHttpTest#%s expected=%s exit=%s\n' "$method" "$count" "$rc" | tee "$out/$method.receipt"
    if (( rc != 0 )); then
        failed=1
        tail -c 40000 "$out/$method.log"
    fi
done < "$out/inventory"
exit "$failed"
