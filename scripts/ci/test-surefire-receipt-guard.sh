#!/usr/bin/env bash
# Source-free negative matrix; does not inspect or transfer private compiler source.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/../.." && pwd -P)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/reports"
javac -d "$tmp/classes" "$root/scripts/ci/SurefireReceiptGuard.java"
run() { java -cp "$tmp/classes" SurefireReceiptGuard "$tmp/reports" "$tmp/receipt.tsv" ACheck BCheck; }
reject() {
  local name="$1"
  if run >"$tmp/stdout" 2>"$tmp/stderr"; then
    echo "UNEXPECTED VALID RECEIPT: $name" >&2
    exit 1
  fi
  echo "REJECTED: $name"
}
cat > "$tmp/reports/TEST-a.ACheck.xml" <<'XML'
<testsuite name="a.ACheck" tests="2" skipped="1" errors="0" failures="0"><testcase name="one"/><testcase name="two"><skipped/></testcase></testsuite>
XML
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="1" skipped="0" errors="0" failures="0"><testcase name="one"/></testsuite>
XML
run >/dev/null
grep -q $'format=surefire-executed-v1\texecuted=2' "$tmp/receipt.tsv"
echo 'PASS: legitimate mixed executed and skipped'
cp "$tmp/reports/TEST-a.BCheck.xml" "$tmp/valid.xml"
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="1" skipped="1" errors="0" failures="0"><testcase name="one"><skipped/></testcase></testsuite>
XML
reject 'all skipped'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="0" skipped="0" errors="0" failures="0"/>
XML
reject 'zero executed tests'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="1" skipped="0" errors="0" failures="1"><testcase name="one"><failure/></testcase></testsuite>
XML
reject 'failing JUnit suite'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="20" skipped="0" errors="0" failures="0"><testcase name="one"/></testsuite>
XML
reject 'summary-only inflated test count'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><testsuite name="a.BCheck" tests="1" skipped="0" errors="0" failures="0"><testcase name="&xxe;"/></testsuite>
XML
reject 'DOCTYPE / external entity'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="1" skipped="0" errors="0" failures="0"><testcase name="one"><failure message="hidden"/></testcase></testsuite>
XML
reject 'hidden failure node under green suite'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="1" skipped="0" errors="0" failures="0"><testcase name="one"><error message="hidden"/></testcase></testsuite>
XML
reject 'hidden error node under green suite'
cat > "$tmp/reports/TEST-a.BCheck.xml" <<'XML'
<testsuite name="a.BCheck" tests="2" skipped="0" errors="0" failures="0"><testcase classname="a.BCheck" name="same"/><testcase classname="a.BCheck" name="same"/></testsuite>
XML
reject 'duplicated testcase identity despite green counts'
cp "$tmp/valid.xml" "$tmp/reports/TEST-a.BCheck.xml"
cp "$tmp/valid.xml" "$tmp/reports/TEST-b.BCheck.xml"
reject 'duplicate suite reports'
rm "$tmp/reports/TEST-b.BCheck.xml"
mv "$tmp/reports/TEST-a.BCheck.xml" "$tmp/target.xml"
ln -s "$tmp/target.xml" "$tmp/reports/TEST-a.BCheck.xml"
reject 'symlinked report'
rm "$tmp/reports/TEST-a.BCheck.xml"
reject 'missing required suite'
echo 'PASS: eleven fail-closed negative cases'
