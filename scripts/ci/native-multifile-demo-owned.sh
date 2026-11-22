#!/usr/bin/env bash
# Compiler-owned exact-source gate. Never execute downloaded demo shell scripts.
set -euo pipefail
umask 077
fail() { echo "ATTO_OWNED_GATE_FAIL: $*" >&2; exit 1; }
valid_sha() { [[ "$1" =~ ^[a-f0-9]{40}$ ]]; }

# Negative checks require the compiler's --check diagnostic for the exact
# source file. JVM/Maven infrastructure failures are not semantic passes.
oracle() {
  local kind="$1" rc="$2" source="$3" out="$4" err="$5" line
  [[ "$rc" == 1 && -f "$out" && ! -L "$out" && ! -s "$out" ]] || return 1
  [[ -f "$err" && ! -L "$err" ]] || return 1
  (( $(wc -c < "$err") <= 8192 )) || return 1
  (( $(wc -l < "$err") == 1 )) || return 1
  IFS= read -r line < "$err"
  [[ "$line" == "$source:"* ]] || return 1
  [[ "$line" =~ :[0-9]+:[0-9]+:\ error:\  ]] || return 1
  case "$kind" in
    missing-import) [[ "$line" == *'does not resolve to a file'* ]] ;;
    ambiguous-import) [[ "$line" == *'collide'* && "$line" == *'is ambiguous in'* ]] ;;
    ownership-conflict) [[ "$line" == *'overlapping mutable borrow arguments'* ]] ;;
    aliased-module-leak) [[ "$line" == *'unknown class transport_contract.Request'* ]] ;;
    *) return 1 ;;
  esac || return 1
  [[ "$line" != *'NoClassDefFoundError'* && "$line" != *'Failed to execute goal'* ]]
}

self_test() {
  local t
  t="$(mktemp -d)"
  trap 'rm -rf -- "$t"' RETURN
  : > "$t/out"
  local entry="$t/missing-helper.atto"
  printf '%s\n' "$entry:1:1: error: import foo does not resolve to a file" > "$t/err"
  oracle missing-import 1 "$entry" "$t/out" "$t/err" || fail "valid diagnostic rejected"
  if oracle missing-import 124 "$entry" "$t/out" "$t/err"; then fail "timeout accepted"; fi
  if oracle missing-import 0 "$entry" "$t/out" "$t/err"; then fail "exit zero accepted"; fi
  printf '%s\n' '[ERROR] Failed to execute goal: import foo does not resolve to a file' > "$t/err"
  if oracle missing-import 1 "$entry" "$t/out" "$t/err"; then fail "Maven failure accepted"; fi
  printf '%s\n' "$entry:1:1: error: unrelated syntax" > "$t/err"
  if oracle missing-import 1 "$entry" "$t/out" "$t/err"; then fail "wrong error accepted"; fi
  printf '%s\n' "$entry:1:1: error: import foo does not resolve to a file" > "$t/err"
  printf '42' > "$t/out"
  if oracle missing-import 1 "$entry" "$t/out" "$t/err"; then fail "stdout accepted"; fi
  echo "ATTO_OWNED_SOURCE_FREE_SELF_TEST_PASS accepts=1 rejects=5 NOT_EXECUTED"
}

(( $# == 1 )) || { echo "usage: $0 --self-test|--run" >&2; exit 64; }
case "$1" in
  --self-test) self_test; exit 0 ;;
  --run) ;;
  *) echo "usage: $0 --self-test|--run" >&2; exit 64 ;;
esac

: "$ATTO_POLICY_DIR" "$ATTO_COMPILER_DIR" "$ATTO_DEMO_DIR"
: "$ATTO_EXPECTED_POLICY_SHA" "$ATTO_EXPECTED_COMPILER_SHA" "$ATTO_EXPECTED_DEMO_SHA"
: "$ATTO_RECEIPT_DIR"
for sha in "$ATTO_EXPECTED_POLICY_SHA" "$ATTO_EXPECTED_COMPILER_SHA" "$ATTO_EXPECTED_DEMO_SHA"; do
  valid_sha "$sha" || fail "invalid source SHA"
done
for cmd in git java mvn timeout sha256sum cmp find sort sudo unshare setpriv stat; do
  command -v "$cmd" >/dev/null || fail "missing prerequisite $cmd"
done
: "${JAVA_HOME:?JDK 25 JAVA_HOME is required}"
[[ -x "$JAVA_HOME/bin/java" ]] || fail "missing pinned JDK java launcher"
for dir in "$ATTO_POLICY_DIR" "$ATTO_COMPILER_DIR" "$ATTO_DEMO_DIR" "$ATTO_RECEIPT_DIR"; do
  [[ "$dir" =~ ^[A-Za-z0-9_./-]+$ ]] || fail "unsafe path"
  [[ ! -L "$dir" && -d "$dir" ]] || fail "symlinked or missing directory"
done
policy="$(cd "$ATTO_POLICY_DIR" && pwd -P)"
compiler="$(cd "$ATTO_COMPILER_DIR" && pwd -P)"
demo="$(cd "$ATTO_DEMO_DIR" && pwd -P)"
receipt="$(cd "$ATTO_RECEIPT_DIR" && pwd -P)"
# The workflow may have created this before the runner script set umask=077.
# Ensure child processes running as UID 65534 cannot read/write receipts.
chmod 0700 -- "$receipt"
[[ "$(stat -c '%a' -- "$receipt")" == 700 ]] ||
  fail "receipt directory is not private to the trusted runner"

verify_checkout() {
  local path="$1" expected="$2"
  [[ "$(git -C "$path" rev-parse HEAD)" == "$expected" ]] ||
    fail "wrong checkout HEAD $path"
  [[ "$(git -C "$path" rev-parse --show-toplevel)" == "$path" ]] ||
    fail "checkout is not repository root $path"
  [[ -z "$(git -C "$path" status --porcelain --untracked-files=all)" ]] ||
    fail "checkout dirty before test $path"
}
verify_checkout "$policy" "$ATTO_EXPECTED_POLICY_SHA"
verify_checkout "$compiler" "$ATTO_EXPECTED_COMPILER_SHA"
verify_checkout "$demo" "$ATTO_EXPECTED_DEMO_SHA"

[[ -f "$demo/native-multifile/JAVA_COMPILER_REF" ]] || fail "missing compiler pin"
[[ "$(cat "$demo/native-multifile/JAVA_COMPILER_REF")" == "$ATTO_EXPECTED_COMPILER_SHA" ]] ||
  fail "demo pin does not match compiler SHA"
[[ -z "$(find "$demo/native-multifile" -type l -print -quit)" ]] ||
  fail "symlinked demo source"
[[ -f "$compiler/pom.xml" ]] || fail "compiler pom missing"
for f in src/main.atto module/main.atto negative/ownership-valid.atto \
         negative/missing-helper.atto negative/ambiguous-import.atto \
         negative/ownership-conflict.atto module/alias-leak.atto; do
  [[ -s "$demo/native-multifile/$f" ]] || fail "missing .atto case $f"
done

{
  printf 'status=NOT_EXECUTED\n'
  printf 'github_run_id=%s\n' "$GITHUB_RUN_ID"
  printf 'github_run_attempt=%s\n' "$GITHUB_RUN_ATTEMPT"
  printf 'github_job=%s\n' "$GITHUB_JOB"
  printf 'policy_head=%s\n' "$ATTO_EXPECTED_POLICY_SHA"
  printf 'policy_tree=%s\n' "$(git -C "$policy" rev-parse 'HEAD^{tree}')"
  printf 'compiler_head=%s\n' "$ATTO_EXPECTED_COMPILER_SHA"
  printf 'compiler_tree=%s\n' "$(git -C "$compiler" rev-parse 'HEAD^{tree}')"
  printf 'demo_head=%s\n' "$ATTO_EXPECTED_DEMO_SHA"
  printf 'demo_tree=%s\n' "$(git -C "$demo" rev-parse 'HEAD^{tree}')"
  printf 'backend=java-graalvm\nprofile=server-jit\n'
  printf 'no_stitch=true\nprivate_source_export=false\n'
  printf 'guest_uid=65534\nguest_network_namespace=true\nguest_no_new_privileges=true\n'
  printf 'guest_environment_scrub=true\nreceipt_directory_mode=700\n'
  printf 'compiler_pom_sha256=%s\n' "$(sha256sum "$compiler/pom.xml" | cut -d ' ' -f1)"
} > "$receipt/identity.txt"
find "$demo/native-multifile" -type f -name '*.atto' -print0 |
  sort -z | xargs -0 -r sha256sum > "$receipt/fixture-sources.sha256"
test -s "$receipt/fixture-sources.sha256"

# Compile only inside the private compiler's own repository checkout.
if ! env -u ORESLANG_PATH timeout --signal=TERM --kill-after=5s 600s \
    mvn -B -ntp -q -f "$compiler/pom.xml" -DskipTests clean package \
    > "$receipt/compiler-build.stdout" 2> "$receipt/compiler-build.stderr"; then
  sed -i 's/^status=NOT_EXECUTED$/status=BUILD_FAILED/' "$receipt/identity.txt"
  fail "private compiler build failed; no guest proof"
fi
find "$compiler/target" -maxdepth 1 -type f -name '*.jar' -print0 |
  sort -z | xargs -0 -r sha256sum > "$receipt/compiler-artifacts.sha256"
test -s "$receipt/compiler-artifacts.sha256" || fail "no JAR built"
if ! env -u ORESLANG_PATH timeout --signal=TERM --kill-after=5s 180s \
    mvn -B -ntp -q -f "$compiler/pom.xml" \
    org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
    "-Dmdep.outputFile=$receipt/classpath.txt" \
    > "$receipt/classpath-build.stdout" 2> "$receipt/classpath-build.stderr"; then
  fail "classpath resolution failed; no guest proof"
fi
[[ -s "$receipt/classpath.txt" && -d "$compiler/target/classes" ]] ||
  fail "runtime classpath/bytecode absent"
# Run the compiler-owned, adversarially tested classpath audit.
# Never execute helper scripts fetched from the demo repository.
bash "$policy/scripts/ci/atto-runtime-classpath-audit.sh" \
  "$compiler/target/classes" "$receipt/classpath.txt" "$receipt"
test -s "$receipt/runtime-dependencies.sha256" || fail "empty dependency evidence"
runtime_cp="$compiler/target/classes:$(cat "$receipt/classpath.txt")"
java -version > "$receipt/java.stdout" 2> "$receipt/java.stderr"
# Host-side infrastructure admission, BEFORE entering the guest phase:
# the sandboxed UID must be able to read the pinned JDK, launcher bytecode,
# public demo inputs and every audited runtime JAR. Otherwise an OS permission
# failure could masquerade as a language/compiler semantic failure.
IFS=':' read -r -a sandbox_deps <<< "$(cat "$receipt/classpath.txt")"
(( ${#sandbox_deps[@]} >= 1 )) || fail "no audited sandbox dependencies"
if ! sudo -n env -i PATH=/usr/bin:/bin HOME=/nonexistent TMPDIR=/tmp LANG=C.UTF-8 \
      /usr/bin/unshare --net -- \
      /usr/bin/setpriv --reuid=65534 --regid=65534 \
        --clear-groups --no-new-privs \
      /bin/sh -c '
        test "$(id -u)" -eq 65534 || exit 70
        for path do
          test -r "$path" || { echo "SANDBOX_UNREADABLE_PATH: $path" >&2; exit 70; }
        done
      ' sh "$JAVA_HOME/bin/java" \
      "$compiler/target/classes/dev/oreslang/launcher/OresMain.class" \
      "$demo/native-multifile/src/main.atto" "${sandbox_deps[@]}" \
      > "$receipt/sandbox-readability.stdout" 2> "$receipt/sandbox-readability.stderr"; then
  fail "isolated guest cannot read pinned runtime/input files; no guest verdict"
fi
printf 'case\tphase\texit\tverdict\n' > "$receipt/cases.tsv"
sed -i 's/^status=NOT_EXECUTED$/status=GUEST_FAILED/' "$receipt/identity.txt"

invoke() {
  local label="$1" rc=0
  shift
  # Open stdout/stderr as the trusted parent. The child UID cannot open the
  # parent-owned 0700 receipt directory, and receives no inherited workflow
  # environment/secrets. Its network namespace has no host network routes.
  # Fail closed if the hosted runner cannot create the sandbox.
  (
    ulimit -f 8192
    sudo -n env -i \
      "JAVA_HOME=$JAVA_HOME" "PATH=$JAVA_HOME/bin:/usr/bin:/bin" \
      HOME=/nonexistent TMPDIR=/tmp LANG=C.UTF-8 \
      /usr/bin/unshare --net -- \
      /usr/bin/setpriv --reuid=65534 --regid=65534 \
        --clear-groups --no-new-privs \
      /usr/bin/timeout --signal=TERM --kill-after=5s 120s \
      "$JAVA_HOME/bin/java" -cp "$runtime_cp" \
      dev.oreslang.launcher.OresMain "$@"
  ) > "$receipt/$label.stdout" 2> "$receipt/$label.stderr" || rc=$?
  printf '%s\n' "$rc" > "$receipt/$label.exit"
  return "$rc"
}

positive() {
  local name="$1" entry="$2"
  echo "ATTO_OWNED: checking positive $name"
  invoke "$name-check" --check "$entry" || fail "positive compiler check failed: $name"
  [[ ! -s "$receipt/$name-check.stdout" && ! -s "$receipt/$name-check.stderr" ]] ||
    fail "positive compiler check emitted diagnostics: $name"
  printf '%s\tcheck\t0\tPASS\n' "$name" >> "$receipt/cases.tsv"
  invoke "$name-guest" --mode=jit --platform=server "$entry" ||
    fail "positive guest failed: $name"
  printf '42' > "$receipt/expected"
  cmp -s "$receipt/expected" "$receipt/$name-guest.stdout" ||
    fail "wrong guest stdout: $name"
  [[ ! -s "$receipt/$name-guest.stderr" ]] || fail "unexpected guest stderr: $name"
  printf '%s\tguest\t0\tPASS\n' "$name" >> "$receipt/cases.tsv"
}

negative() {
  local name="$1" entry="$2" rc
  echo "ATTO_OWNED: checking negative $name"
  if invoke "$name-check" --check "$entry"; then
    fail "compiler admitted forbidden source: $name"
  fi
  rc="$(cat "$receipt/$name-check.exit")"
  oracle "$name" "$rc" "$entry" "$receipt/$name-check.stdout" "$receipt/$name-check.stderr" ||
    fail "not a specific compiler source rejection: $name"
  printf '%s\tcheck\t%s\tPASS\n' "$name" "$rc" >> "$receipt/cases.tsv"
}

positive linked-function "$demo/native-multifile/src/main.atto"
positive linked-module-class "$demo/native-multifile/module/main.atto"
positive ownership-valid "$demo/native-multifile/negative/ownership-valid.atto"
negative missing-import "$demo/native-multifile/negative/missing-helper.atto"
negative ambiguous-import "$demo/native-multifile/negative/ambiguous-import.atto"
negative ownership-conflict "$demo/native-multifile/negative/ownership-conflict.atto"
negative aliased-module-leak "$demo/native-multifile/module/alias-leak.atto"
# Do not write PASS to the uploadable artifact until the verifier has
# returned 0. Termination, cancellation, timeout or cleanup during this phase
# must preserve VERIFYING, which cannot be mistaken for completed evidence.
sed -i 's/^status=GUEST_FAILED$/status=VERIFYING/' "$receipt/identity.txt"
printf 'positive_cases=3\nnegative_cases=4\n' >> "$receipt/identity.txt"
if ! bash "$policy/scripts/ci/verify-atto-owned-matrix.sh" --verify \
      "$receipt" "$ATTO_EXPECTED_DEMO_SHA" "$ATTO_EXPECTED_COMPILER_SHA" "$demo"; then
  sed -i 's/^status=VERIFYING$/status=RECEIPT_FAILED/' "$receipt/identity.txt"
  fail 'independent seven-case receipt verification failed'
fi
sed -i 's/^status=VERIFYING$/status=PASS/' "$receipt/identity.txt"
grep -Fxq 'status=PASS' "$receipt/identity.txt" ||
  fail 'terminal verified PASS state not committed'
echo "ATTO_OWNED_GUEST_PASS source=$ATTO_EXPECTED_COMPILER_SHA demo=$ATTO_EXPECTED_DEMO_SHA cases=7 (guest JIT, not AOT)"
