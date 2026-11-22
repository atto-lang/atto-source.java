#!/usr/bin/env bash
# Offline adversarial tests; never fetches private compiler files or sends API writes.
set -euo pipefail
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
mkdir -p "$work/checkout/tools" "$work/bin"
cp "$script_dir/run-nested-extraction-exact-tree.sh" "$work/checkout/tools/"
cp "$script_dir/verify-exact-source-tree.sh" "$work/checkout/tools/"
printf '<project/>\n' > "$work/checkout/pom.xml"
printf 'program\n' > "$work/checkout/source.ores"
cat > "$work/bin/java" <<'MOCKJAVA'
#!/usr/bin/env bash
printf '    java.specification.version = %s\n' "${MOCK_JAVA_MAJOR:-25}" >&2
printf '    java.runtime.name = %s\n' "${MOCK_JAVA_RUNTIME:-Oracle GraalVM}" >&2
printf '    java.vendor.version = %s\n' "${MOCK_JAVA_VENDOR_VERSION:-Oracle Corporation}" >&2
if [[ "${MOCK_JAVA_OMIT_HOME:-0}" != 1 ]]; then
    printf '    java.home = %s\n' "${MOCK_JAVA_HOME_PROP:-/opt/pinned-graalvm25}" >&2
fi
printf '    user.home = %s\n' "${MOCK_JAVA_USER_HOME:-/home/ci}" >&2
MOCKJAVA
cat > "$work/bin/mvn" <<'MOCKMVN'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_MAVEN_LOG"
MOCKMVN
chmod +x "$work/bin/java" "$work/bin/mvn"
export PATH="$work/bin:$PATH" MOCK_MAVEN_LOG="$work/calls"
# The mock PATH Java must control the baseline; inherited runner JAVA_HOME
# cannot be allowed to change this source-free self-test's meaning.
unset JAVA_HOME
cd "$work/checkout"
git init -q
git config user.email ci-test@example.invalid
git config user.name CI-Test
git add . && git commit -qm baseline
expected="$(git rev-parse 'HEAD^{tree}')"
runner=tools/run-nested-extraction-exact-tree.sh
attempt() {
    local expected_status="$1"; shift
    : > "$MOCK_MAVEN_LOG"
    local actual=0
    bash "$runner" "$@" > "$work/out" 2>&1 || actual=$?
    if [[ "$actual" != "$expected_status" ]]; then
        printf 'expected exit %s got %s\n' "$expected_status" "$actual" >&2
        cat "$work/out" >&2
        exit 1
    fi
}
attempt 0 "$expected" . targeted
grep -q -- '-DfailIfNoTests=true test' "$MOCK_MAVEN_LOG"
grep -q -- 'OresFuturesTest,NestedWrapperSemanticsContractTest,' "$MOCK_MAVEN_LOG"
grep -q -- 'NestedExtractionCallableEscapeTest' "$MOCK_MAVEN_LOG"
grep -q -- 'NestedFutureFlattenCycleRegressionTest' "$MOCK_MAVEN_LOG"
grep -q -- 'NestedOptionResultBoundaryRegressionTest' "$MOCK_MAVEN_LOG"
[[ "$(wc -l < "$MOCK_MAVEN_LOG")" -eq 1 ]]
echo 'PASS clean tree invokes targeted Maven once'
attempt 66 '0000000000000000000000000000000000000000' . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
echo 'PASS wrong tree blocks all Maven execution'
printf 'tampered\n' > source.ores
attempt 66 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
git restore -- source.ores
echo 'PASS changed source blocks Maven execution'
printf 'new\n' > evil.ores
attempt 66 "$expected" . full
[[ ! -s "$MOCK_MAVEN_LOG" ]]
rm evil.ores
echo 'PASS untracked source blocks Maven execution'
export MOCK_JAVA_MAJOR=21
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_JAVA_MAJOR
echo 'PASS non-JDK25 blocks Maven execution'
export MOCK_JAVA_OMIT_HOME=1
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_JAVA_OMIT_HOME
echo 'PASS missing PATH Java home property rejects Maven execution'
export MOCK_JAVA_RUNTIME='OpenJDK Runtime Environment'
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_JAVA_RUNTIME
echo 'PASS non-GraalVM PATH JDK25 blocks Maven execution'
# A generic JVM property containing "GraalVM" cannot authenticate the runtime.
export MOCK_JAVA_RUNTIME='OpenJDK Runtime Environment' MOCK_JAVA_USER_HOME='/opt/GraalVM-not-the-runtime'
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_JAVA_RUNTIME MOCK_JAVA_USER_HOME
echo 'PASS GraalVM string in unrelated PATH property cannot bypass runtime gate'
export MOCK_JAVA_RUNTIME='Java(TM) SE Runtime Environment' MOCK_JAVA_VENDOR_VERSION='Oracle GraalVM 25.0.3'
attempt 0 "$expected" . targeted
grep -q -- '-DfailIfNoTests=true test' "$MOCK_MAVEN_LOG"
unset MOCK_JAVA_RUNTIME MOCK_JAVA_VENDOR_VERSION
echo 'PASS Oracle GraalVM vendor identity accepts a standard Java runtime name' 
# java.vendor.version is externally mutable (e.g. JAVA_TOOL_OPTIONS=-D...)
# and must not be accepted as identity evidence under option injection.
for ambient in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    printf -v "$ambient" '%s' '-Djava.vendor.version=Oracle GraalVM'
    export "$ambient"
    attempt 69 "$expected" . targeted
    [[ ! -s "$MOCK_MAVEN_LOG" ]]
    unset "$ambient"
    echo "PASS $ambient injection forbidden before Maven"
done
mkdir -p "$work/java-home-21/bin" "$work/java-home-25/bin"
cat > "$work/java-home-21/bin/java" <<'BADHOMEJAVA'
#!/usr/bin/env bash
printf '    java.specification.version = 21\n' >&2
BADHOMEJAVA
cat > "$work/java-home-25/bin/java" <<'GOODHOMEJAVA'
#!/usr/bin/env bash
printf '    java.specification.version = 25\n' >&2
printf '    java.runtime.name = %s\n' "${MOCK_HOME_JAVA_RUNTIME:-Oracle GraalVM}" >&2
printf '    java.vendor.version = %s\n' "${MOCK_HOME_JAVA_VENDOR_VERSION:-Oracle Corporation}" >&2
if [[ "${MOCK_HOME_JAVA_OMIT_HOME:-0}" != 1 ]]; then
    printf '    java.home = %s\n' "${MOCK_HOME_JAVA_HOME_PROP:-/opt/pinned-graalvm25}" >&2
fi
printf '    user.home = %s\n' "${MOCK_HOME_JAVA_USER_HOME:-/home/ci}" >&2
GOODHOMEJAVA
chmod +x "$work/java-home-21/bin/java" "$work/java-home-25/bin/java"
export JAVA_HOME="$work/java-home-21"
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
echo 'PASS mismatched JAVA_HOME rejects Maven despite PATH JDK25'
export JAVA_HOME="$work/java-home-25"
attempt 0 "$expected" . targeted
grep -q -- '-DfailIfNoTests=true test' "$MOCK_MAVEN_LOG"
echo 'PASS matching JAVA_HOME GraalVM JDK25 invokes Maven'
# Same GraalVM 25 vendor and major version do not prove the same JDK image.
export MOCK_HOME_JAVA_HOME_PROP='/opt/unreviewed-second-graalvm25'
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_HOME_JAVA_HOME_PROP
echo 'PASS distinct GraalVM JDK25 installations cannot be mixed'
export MOCK_HOME_JAVA_OMIT_HOME=1
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_HOME_JAVA_OMIT_HOME
echo 'PASS missing JAVA_HOME Java home property rejects Maven execution'

export MOCK_HOME_JAVA_RUNTIME='OpenJDK Runtime Environment'
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_HOME_JAVA_RUNTIME
echo 'PASS non-GraalVM JAVA_HOME JDK25 blocks Maven execution'
export JAVA_HOME="$work/java-home-25"
export MOCK_HOME_JAVA_RUNTIME='OpenJDK Runtime Environment'
# A fake version-banner field must not bypass the runtime-specific check.
export MOCK_HOME_JAVA_USER_HOME='/opt/GraalVM-toolchain-bait'
attempt 69 "$expected" . targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
unset MOCK_HOME_JAVA_RUNTIME MOCK_HOME_JAVA_USER_HOME JAVA_HOME
echo 'PASS GraalVM string in unrelated HOME toolchain property cannot bypass runtime gate'
export JAVA_HOME="$work/java-home-25"
export MOCK_HOME_JAVA_RUNTIME='Java(TM) SE Runtime Environment' MOCK_HOME_JAVA_VENDOR_VERSION='Oracle GraalVM 25.0.3'
attempt 0 "$expected" . targeted
grep -q -- '-DfailIfNoTests=true test' "$MOCK_MAVEN_LOG"
unset MOCK_HOME_JAVA_RUNTIME MOCK_HOME_JAVA_VENDOR_VERSION JAVA_HOME
echo 'PASS Oracle GraalVM JAVA_HOME vendor identity accepts a standard Java runtime name' 
unset JAVA_HOME
attempt 64 "$expected" . '../../outside' targeted
[[ ! -s "$MOCK_MAVEN_LOG" ]]
echo 'PASS source path traversal blocked'
attempt 64 "$expected" . 'arbitrary-shell-command'
[[ ! -s "$MOCK_MAVEN_LOG" ]]
echo 'PASS unknown phase blocked'
attempt 0 "$expected" . full
grep -q -- '-Dores.runtime.carriers=native verify' "$MOCK_MAVEN_LOG"
echo 'PASS full verifier invokes Maven only after tree proof'
echo 'NESTED_EXTRACTION_RUNNER_SELFTEST_PASS'
