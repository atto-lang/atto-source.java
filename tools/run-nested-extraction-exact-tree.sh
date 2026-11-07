#!/usr/bin/env bash
# Run Oreslang nested Option/Future gates only from a separately attested Git tree.
# Intended for source-org CI or an independently approved private mirror.
set -euo pipefail

die() { printf 'NESTED_EXTRACTION_CI_REJECTED: %s\n' "$1" >&2; exit "${2:-64}"; }
[[ $# -eq 3 ]] || die 'usage: run-nested-extraction-exact-tree.sh EXPECTED_TREE_SHA .|private-oreslang-source targeted|full|native-aot|native-hybrid|all'
expected="$1"
source_dir="$2"
phase="$3"
[[ "$expected" =~ ^[0-9a-f]{40}$ ]] || die 'expected tree must be 40 lowercase hex digits'
[[ "$source_dir" == '.' || "$source_dir" == 'private-oreslang-source' ]] || die 'unapproved source directory'
case "$phase" in targeted|full|native-aot|native-hybrid|all) ;; *) die 'unknown test phase' ;; esac
command -v git >/dev/null || die 'git unavailable' 69
root="$(git rev-parse --show-toplevel 2>/dev/null)" || die 'must run inside a Git checkout'
cd "$root"
[[ -d "$source_dir" && ! -L "$source_dir" ]] || die 'missing or symlinked source directory'
[[ -f "$source_dir/pom.xml" && -f "$source_dir/tools/verify-exact-source-tree.sh" ]] || die 'missing checked source or verifier'
# No build commands, downloads or Maven cache access before this proof.
bash "$source_dir/tools/verify-exact-source-tree.sh" "$expected" "$source_dir" || die 'source tree proof failed' 66
printf 'NESTED_EXTRACTION_CI|TREE_VERIFIED|%s|%s\n' "$expected" "$source_dir"
command -v java >/dev/null || die 'Java unavailable' 69
command -v mvn >/dev/null || die 'Maven unavailable' 69
# JVM option injection can forge java.vendor.version on ordinary OpenJDK.
# Refuse ambient options rather than certifying an altered toolchain identity.
for injected in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    if [[ -n "${!injected:-}" ]]; then
        die "ambient JVM option injection forbidden: $injected" 69
    fi
done
java_details="$(java -XshowSettings:properties -version 2>&1)" || die 'Java version probe failed' 69
java_major="$(printf '%s\n' "$java_details" | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -n 1)"
# Pin the actual JVM installation used by the PATH launcher. Maven may
# instead invoke $JAVA_HOME/bin/java, so a matching vendor/version is not
# enough to establish that both executions use the same runtime image.
path_java_home="$(printf '%s\n' "$java_details" | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1)"
[[ -n "$path_java_home" ]] || die 'PATH Java home missing from JVM properties' 69
[[ "$java_major" == '25' ]] || die 'GraalVM JDK 25 required' 69
printf '%s\n' "$java_details" | grep -Eqi '^[[:space:]]*java\.(runtime\.name|vendor\.version)[[:space:]]*=[[:space:]]*.*graalvm' || die 'GraalVM JDK 25 runtime identity required' 69
# Maven may ignore PATH's java and use JAVA_HOME instead. Never certify a
# JDK 25 run if the Maven JVM is pointed at an older/different installation.
if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" ]] || die 'JAVA_HOME does not contain executable java' 69
    home_details="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1)" || die 'JAVA_HOME version probe failed' 69
    home_major="$(printf '%s\n' "$home_details" | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -n 1)"
    home_java_home="$(printf '%s\n' "$home_details" | sed -n 's/^[[:space:]]*java.home = //p' | head -n 1)"
    [[ -n "$home_java_home" && "$home_java_home" == "$path_java_home" ]] \
      || die 'PATH and JAVA_HOME launch distinct Java installations' 69
    [[ "$home_major" == '25' ]] || die 'JAVA_HOME JVM must be JDK 25 for Maven' 69
    printf '%s\n' "$home_details" | grep -Eqi '^[[:space:]]*java\.(runtime\.name|vendor\.version)[[:space:]]*=[[:space:]]*.*graalvm' || die 'JAVA_HOME must use GraalVM JDK 25 runtime' 69
fi
if [[ "$phase" == native-* || "$phase" == all ]]; then
    [[ "$(uname -s)" == Linux ]] || die 'native smoke phase requires Linux' 69
    command -v native-image >/dev/null || die 'GraalVM native-image unavailable' 69
    native-image --version
fi
cd "$source_dir"
run_targeted() {
    local tests='OresFuturesTest,NestedWrapperSemanticsContractTest,AffineSumProjectionExtractionTest,BorrowedSumExtractionOwnershipTest,CheckedOptionDepthMetadataTest,NestedExtractionCallableEscapeTest,FutureCompositionLanguageTest,NestedExtractionArityDiagnosticsTest,NestedFutureAffineReceiverTest,NestedFutureFlattenArityTest,NestedFutureSchedulerContractTest,NestedFutureCycleInterleavingTest,NestedFutureFlattenCycleRegressionTest,NestedOptionDepthConformanceTest,NestedOptionFutureExtractionLanguageTest,NestedOptionNoneBoundaryTest,NestedOptionResultBoundaryRegressionTest'
    mvn -B -ntp "-Dtest=$tests" -DfailIfNoTests=true test
}
run_full() { mvn -B -ntp -Dores.runtime.carriers=native verify; }
run_aot() {
    mvn -B -ntp -Pnative-aot -DskipTests package
    test -x ./target/ores-aot || die 'Native AOT output missing' 70
    ./target/ores-aot --mode=aot --platform=linux examples/together.ores
}
run_hybrid() {
    mvn -B -ntp -Pnative-hybrid -DskipTests package
    test -x ./target/ores-hybrid || die 'Hybrid Native Image output missing' 70
    ./target/ores-hybrid --mode=hybrid --platform=linux examples/together.ores
}
case "$phase" in
    targeted) run_targeted ;;
    full) run_full ;;
    native-aot) run_aot ;;
    native-hybrid) run_hybrid ;;
    all) run_targeted; run_full; run_aot; run_hybrid ;;
esac
printf 'NESTED_EXTRACTION_CI|PHASE_PASS|%s|%s\n' "$phase" "$expected"
