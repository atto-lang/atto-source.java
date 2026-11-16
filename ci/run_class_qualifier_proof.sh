#!/usr/bin/env bash
# Exact-tree JVM proof for implicit self, pkg::, and imports:: on approved CI only.
# This script does NOT approve or transfer private source to another repository.
set -euo pipefail

die() { printf 'class-qualifier-proof: FAIL: %s\n' "$*" >&2; exit 1; }

: "${EXPECTED_SOURCE_TREE_SHA:?An independently recorded 40-hex source Git tree SHA is required}"
: "${EXPECTED_SOURCE_COMMIT:?The pinned source Git commit SHA is required for provenance}"
[[ "${EXPECTED_SOURCE_TREE_SHA}" =~ ^[0-9a-f]{40}$ ]] || die "invalid EXPECTED_SOURCE_TREE_SHA"
[[ "${EXPECTED_SOURCE_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || die "invalid EXPECTED_SOURCE_COMMIT"

command -v git >/dev/null || die "git unavailable"
command -v java >/dev/null || die "Java unavailable"
command -v mvn >/dev/null || die "Maven unavailable"

actual_tree="$(git rev-parse 'HEAD^{tree}')"
[[ "$actual_tree" == "$EXPECTED_SOURCE_TREE_SHA" ]] || die "mirror tree SHA mismatch: expected $EXPECTED_SOURCE_TREE_SHA actual $actual_tree"
git diff --quiet --exit-code || die "working tree has tracked changes"
git diff --cached --quiet --exit-code || die "index is dirty"
[[ -z "$(git ls-files --others --exclude-standard)" ]] || die "untracked files present"
[[ -f src/test/java/dev/oreslang/ImplicitSelfMethodCallTest.java ]] || die "implicit-self regression tests missing"
[[ -f src/test/java/dev/oreslang/ImportsQualifiedCallTest.java ]] || die "imports regression tests missing"
[[ -f .github/private-ci-approved-repositories.txt ]] || die "source security policy missing"

printf 'SOURCE_COMMIT=%s\nEXACT_SOURCE_TREE=%s\nMIRROR_COMMIT=%s\n' \
  "$EXPECTED_SOURCE_COMMIT" "$actual_tree" "$(git rev-parse HEAD)"
java -version
mvn -version

# The exact JUnit method names are intentionally not manufactured from source
# greps. Surefire must execute both real classes and report zero skipped cases.
mvn -B -ntp -Dtest=ImplicitSelfMethodCallTest,ImportsQualifiedCallTest \
  -DfailIfNoTests=true test

report_dir=target/surefire-reports
total=0
for klass in ImplicitSelfMethodCallTest ImportsQualifiedCallTest; do
  report="$report_dir/TEST-dev.oreslang.$klass.xml"
  [[ -f "$report" ]] || die "missing Surefire report for $klass"
  header="$(grep -m1 '<testsuite ' "$report" || true)"
  [[ -n "$header" ]] || die "missing testsuite element for $klass"
  case "$header" in *"name=\"dev.oreslang.$klass\""*) ;; *) die "test suite name mismatch for $klass";; esac
  tests="$(printf '%s\n' "$header" | sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p')"
  failures="$(printf '%s\n' "$header" | sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p')"
  errors="$(printf '%s\n' "$header" | sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p')"
  skipped="$(printf '%s\n' "$header" | sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p')"
  [[ "$tests" =~ ^[0-9]+$ && "$failures" == 0 && "$errors" == 0 && "$skipped" == 0 ]] \
    || die "incomplete or failing Surefire data for $klass"
  (( total += tests ))
  printf 'SUITE=%s TESTS=%s FAILURES=%s ERRORS=%s SKIPPED=%s\n' \
    "$klass" "$tests" "$failures" "$errors" "$skipped"
done

# Executable guest-level fixture exercises all three scopes and the imported
# file's public ABI. This is distinct from JUnit's in-process linked runner.
fixture=ci/fixtures/class-qualifiers/main.ores
[[ -f "$fixture" && -f ci/fixtures/class-qualifiers/imported.ores ]] \
  || die "linked namespace guest fixture missing"
jvm_output="$(mvn -q -ntp -DskipTests exec:java -Dexec.args="$fixture")" \
  || die "JVM guest namespace conformance execution failed"
[[ "$jvm_output" == *"7|7|99|23"* ]] \
  || die "JVM guest namespace conformance mismatch: $jvm_output"
printf 'JVM_GUEST_SCOPES=PASS OUTPUT=7|7|99|23\n'

# The AOT proof is optional only for targeted JVM jobs. To claim Native Image
# proof set CLASS_QUALIFIER_PROFILE=aot. Unsupported values fail closed.
case "${CLASS_QUALIFIER_PROFILE:-jvm}" in
  jvm) ;;
  aot)
    command -v native-image >/dev/null || die "GraalVM native-image unavailable"
    mvn -B -ntp -Pnative-aot -DskipTests package
    [[ -x target/ores-aot ]] || die "native executable target/ores-aot missing"
    aot_output="$(./target/ores-aot --mode=aot --platform=linux "$fixture")" \
      || die "AOT guest namespace conformance execution failed"
    [[ "$aot_output" == *"7|7|99|23"* ]] \
      || die "AOT guest namespace conformance mismatch: $aot_output"
    printf 'AOT_GUEST_SCOPES=PASS OUTPUT=7|7|99|23\n'
    ;;
  hybrid)
    command -v native-image >/dev/null || die "GraalVM native-image unavailable"
    mvn -B -ntp -Pnative-hybrid -DskipTests package
    [[ -x target/ores-hybrid ]] || die "native executable target/ores-hybrid missing"
    hybrid_output="$(./target/ores-hybrid --mode=hybrid --platform=linux "$fixture")" \
      || die "Hybrid guest namespace conformance execution failed"
    [[ "$hybrid_output" == *"7|7|99|23"* ]] \
      || die "Hybrid guest namespace conformance mismatch: $hybrid_output"
    printf 'HYBRID_GUEST_SCOPES=PASS OUTPUT=7|7|99|23\n'
    ;;
  *) die "unsupported CLASS_QUALIFIER_PROFILE, expected jvm, aot or hybrid" ;;
esac

# The count is pinned to the currently committed 18 + 16 regression tests.
# Any new head that adds/removes cases must independently update this gate.
[[ "$total" -eq 34 ]] || die "regression count mismatch: expected 34, got $total"
printf 'CLASS_QUALIFIER_PROOF=PASS SOURCE_COMMIT=%s EXACT_SOURCE_TREE=%s TESTS=%s\n' \
  "$EXPECTED_SOURCE_COMMIT" "$actual_tree" "$total"
