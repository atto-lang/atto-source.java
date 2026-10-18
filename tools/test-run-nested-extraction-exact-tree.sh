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
MOCKJAVA
cat > "$work/bin/mvn" <<'MOCKMVN'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_MAVEN_LOG"
MOCKMVN
chmod +x "$work/bin/java" "$work/bin/mvn"
export PATH="$work/bin:$PATH" MOCK_MAVEN_LOG="$work/calls"
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
