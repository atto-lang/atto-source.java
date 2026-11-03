#!/usr/bin/env bash
# Synthetic, source-free adversarial proof for the standalone Surefire receipt gate.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/reports"
expected=(
  'dev.oreslang.DurableActivityWorkflowDeclarationTest:9'
  'dev.oreslang.DurableWorkflowSemanticAnalysisTest:19'
  'dev.oreslang.AttoSourceExtensionTest:5'
  'dev.oreslang.launcher.AttoCliSourceExtensionTest:2'
)
write_suite() {
  local classname="$1" count="$2" fail="${3:-0}" skipped="${4:-0}" body='' n
  for ((n=1;n<=count;n++)); do
    body+="<testcase classname=\"$classname\" name=\"case-$n\"/>"
  done
  if [[ "$classname" == "dev.oreslang.DurableWorkflowSemanticAnalysisTest" ]]; then
    local payload='{"format":"atto.durable.manifest.v1","activities":[],"workflows":[]}'
    local digest hex
    digest="$(printf '%s' "$payload" | sha256sum | cut -d' ' -f1)"
    hex="$(printf '%s' "$payload" | od -An -tx1 | tr -d ' \n')"
    body+="<system-out>DURABLE_M0_MANIFEST_SHA256=$digest&#10;DURABLE_M0_MANIFEST_HEX=$hex</system-out>"
  fi
  printf '<testsuite name="%s" tests="%d" failures="%d" errors="0" skipped="%d">%s</testsuite>\n' \
    "$classname" "$count" "$fail" "$skipped" "$body" > "$work/reports/TEST-$classname.xml"
}
for pair in "${expected[@]}"; do write_suite "${pair%:*}" "${pair#*:}"; done
java "$script_dir/DurableM0ReceiptVerifier.java" "$work/reports"
should_reject() {
  local name="$1"
  if java "$script_dir/DurableM0ReceiptVerifier.java" "$work/reports" >"$work/out" 2>&1; then
    echo "RECEIPT_NEGATIVE_NOT_CAUGHT=$name" >&2; exit 1
  fi
  echo "RECEIPT_NEGATIVE_CAUGHT=$name"
}
# Inflated suite metadata without additional actual testcase nodes.
sed -i 's/tests="5"/tests="6"/' "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml"
should_reject inflated-count
write_suite dev.oreslang.AttoSourceExtensionTest 5
# Hidden per-test failure must not be masked by fabricated zero-failure metadata.
sed -i 's@case-1"/>@case-1"><failure/></testcase>@' "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml"
should_reject hidden-failure
write_suite dev.oreslang.AttoSourceExtensionTest 5 0 1
should_reject skipped-suite
write_suite dev.oreslang.AttoSourceExtensionTest 5
rm "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml"
should_reject missing-report
write_suite dev.oreslang.AttoSourceExtensionTest 5
printf '<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><testsuite>&xxe;</testsuite>\n' > "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml"
should_reject forbidden-doctype
write_suite dev.oreslang.AttoSourceExtensionTest 5
mv "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml" "$work/replacement.xml"
ln -s "$work/replacement.xml" "$work/reports/TEST-dev.oreslang.AttoSourceExtensionTest.xml"
should_reject symlink-report
write_suite dev.oreslang.DurableWorkflowSemanticAnalysisTest 19
sed -i 's/DURABLE_M0_MANIFEST_SHA256=./DURABLE_M0_MANIFEST_SHA256=f/' "$work/reports/TEST-dev.oreslang.DurableWorkflowSemanticAnalysisTest.xml"
should_reject forged-manifest-digest
write_suite dev.oreslang.DurableWorkflowSemanticAnalysisTest 19
sed -i 's@DURABLE_M0_MANIFEST_HEX=@INVALID_MANIFEST_HEX=@' "$work/reports/TEST-dev.oreslang.DurableWorkflowSemanticAnalysisTest.xml"
should_reject absent-manifest-hex
