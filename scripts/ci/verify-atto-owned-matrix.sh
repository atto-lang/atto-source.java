#!/usr/bin/env bash
# Independent compiler-owned gate for seven real shell-launched guest cases.
# This is not a fabricated Surefire suite; those are checked separately.
set -euo pipefail
reject() { echo "ATTO_MATRIX_RECEIPT_REJECT: $*" >&2; return 1; }
check_ledger() {
  local d="$1" entry="$2" label="$3" expected_rc="$4"
  local file="$d/$entry-$label.exit"
  [[ -f "$file" && ! -L "$file" ]] || { reject "missing exit for $label/$entry"; return 1; }
  [[ "$(cat "$file")" == "$expected_rc" ]] || { reject "wrong exit for $label/$entry"; return 1; }
  for suffix in stdout stderr; do
    [[ -f "$d/$entry-$label.$suffix" && ! -L "$d/$entry-$label.$suffix" ]] ||
      { reject "missing output stream for $label/$entry"; return 1; }
  done
}
check_cases() {
  local d="$1" demo="$2" rc out err name file entry pattern
  [[ -d "$d" && ! -L "$d" ]] || { reject "receipt directory absent"; return 1; }
  [[ -f "$d/cases.tsv" && ! -L "$d/cases.tsv" ]] || { reject "case ledger absent"; return 1; }
  # Byte-exact comparison, including final newline. Command substitution
  # strips trailing newlines and otherwise admits an appended blank ledger row.
  cmp -s "$d/cases.tsv" <(
    printf 'case\tphase\texit\tverdict\n'
    for name in linked-function linked-module-class ownership-valid; do
      printf '%s\tcheck\t0\tPASS\n' "$name"
      printf '%s\tguest\t0\tPASS\n' "$name"
    done
    for name in missing-import ambiguous-import ownership-conflict aliased-module-leak; do
      printf '%s\tcheck\t1\tPASS\n' "$name"
    done
  ) || { reject "case ledger missing, duplicate, reordered or forged status"; return 1; }
  for name in linked-function linked-module-class ownership-valid; do
    check_ledger "$d" "$name" check 0 || return 1
    check_ledger "$d" "$name" guest 0 || return 1
    [[ ! -s "$d/$name-check.stdout" && ! -s "$d/$name-check.stderr" ]] ||
      { reject "positive source check emitted diagnostics"; return 1; }
    [[ ! -s "$d/$name-guest.stderr" && "$(cat "$d/$name-guest.stdout")" == 42 ]] ||
      { reject "positive guest output not exact 42"; return 1; }
    [[ $(wc -c < "$d/$name-guest.stdout") -eq 2 ]] ||
      { reject "positive guest stdout must be exact 42 without newline"; return 1; }
  done
  for name in missing-import ambiguous-import ownership-conflict aliased-module-leak; do
    check_ledger "$d" "$name" check 1 || return 1
    [[ ! -s "$d/$name-check.stdout" ]] || { reject "negative guest stdout"; return 1; }
    err="$d/$name-check.stderr"
    [[ $(wc -l < "$err") -eq 1 && $(wc -c < "$err") -le 8192 ]] ||
      { reject "negative requires one bounded diagnostic"; return 1; }
    case "$name" in
      missing-import) entry="$demo/native-multifile/negative/missing-helper.atto"; pattern='does not resolve to a file' ;;
      ambiguous-import) entry="$demo/native-multifile/negative/ambiguous-import.atto"; pattern='is ambiguous in' ;;
      ownership-conflict) entry="$demo/native-multifile/negative/ownership-conflict.atto"; pattern='overlapping mutable borrow arguments' ;;
      aliased-module-leak) entry="$demo/native-multifile/module/alias-leak.atto"; pattern='unknown class transport_contract.Request' ;;
    esac
    IFS= read -r file < "$err"
    [[ "$file" == "$entry:"* && "$file" == *"$pattern"* ]] ||
      { reject "negative lacks anchored compiler-owned diagnostic: $name"; return 1; }
    [[ "$file" =~ :[0-9]+:[0-9]+:\ error:\  ]] ||
      { reject "not compiler --check source diagnostic: $name"; return 1; }
    [[ "$file" != *'NoClassDefFoundError'* && "$file" != *'Failed to execute goal'* ]] ||
      { reject "negative is infrastructure error: $name"; return 1; }
  done
}
verify() {
  local receipt="$1" demo_sha="$2" compiler_sha="$3" demo="$4" status
  local policy_sha="${ATTO_EXPECTED_POLICY_SHA:-}"
  local run_id="${GITHUB_RUN_ID:-}" run_attempt="${GITHUB_RUN_ATTEMPT:-}" job="${GITHUB_JOB:-}"
  [[ "$demo_sha" =~ ^[a-f0-9]{40}$ && "$compiler_sha" =~ ^[a-f0-9]{40}$ &&
     "$policy_sha" =~ ^[a-f0-9]{40}$ ]] ||
    { reject 'invalid exact demo/compiler/policy head identifier'; return 1; }
  [[ "$run_id" =~ ^[0-9]{1,20}$ && "$run_attempt" =~ ^[1-9][0-9]{0,6}$ &&
     "$job" == 'java-linked-guest' ]] ||
    { reject 'missing/invalid GitHub Actions job-attempt identity'; return 1; }
  [[ -f "$receipt/identity.txt" && ! -L "$receipt/identity.txt" ]] ||
    { reject "missing identity receipt"; return 1; }
  [[ $(grep -Ec '^status=' "$receipt/identity.txt") -eq 1 ]] ||
    { reject 'ambiguous receipt status'; return 1; }
  grep -Fxq 'status=PASS' "$receipt/identity.txt" ||
    { reject 'guest matrix did not finish'; return 1; }
  grep -Fxq "demo_head=$demo_sha" "$receipt/identity.txt" ||
    { reject 'demo pin mismatch'; return 1; }
  grep -Fxq "compiler_head=$compiler_sha" "$receipt/identity.txt" ||
    { reject 'compiler pin mismatch'; return 1; }
  # A successful receipt from an older GitHub run, another attempt or a
  # different workflow job is not evidence for this execution.
  for line in "policy_head=$policy_sha" "github_run_id=$run_id" \
              "github_run_attempt=$run_attempt" "github_job=$job"; do
    grep -Fxq "$line" "$receipt/identity.txt" ||
      { reject "identity mismatch: ${line%%=*}"; return 1; }
  done
  grep -Fxq 'positive_cases=3' "$receipt/identity.txt" ||
    { reject 'positive case count mismatch'; return 1; }
  grep -Fxq 'negative_cases=4' "$receipt/identity.txt" ||
    { reject 'negative case count mismatch'; return 1; }
  for file in fixture-sources.sha256 compiler-artifacts.sha256 compiler-classes.sha256 runtime-dependencies.sha256; do
    [[ -s "$receipt/$file" && ! -L "$receipt/$file" ]] ||
      { reject "missing hash receipt: $file"; return 1; }
    sha256sum --status -c "$receipt/$file" ||
      { reject "hash mismatch: $file"; return 1; }
  done
  check_cases "$receipt" "$demo" ||
    { reject 'case ledger verification failed'; return 1; }
  echo "ATTO_OWNED_MATRIX_RECEIPT_PASS demo=$demo_sha compiler=$compiler_sha cases=7 JIT_ONLY_NOT_AOT"
}
self_test() {
  local t name entry reason
  t="$(mktemp -d)"
  trap 'rm -rf -- "$t"' EXIT
  mkdir -p "$t/native-multifile/negative" "$t/native-multifile/module"
  local p="$t/cases.tsv"
  printf 'case\tphase\texit\tverdict\n' > "$p"
  for name in linked-function linked-module-class ownership-valid; do
    printf '%s\tcheck\t0\tPASS\n%s\tguest\t0\tPASS\n' "$name" "$name" >> "$p"
    printf '0\n' > "$t/$name-check.exit"
    printf '0\n' > "$t/$name-guest.exit"
    : > "$t/$name-check.stdout"
    : > "$t/$name-check.stderr"
    printf '42' > "$t/$name-guest.stdout"
    : > "$t/$name-guest.stderr"
  done
  for name in missing-import ambiguous-import ownership-conflict aliased-module-leak; do
    printf '%s\tcheck\t1\tPASS\n' "$name" >> "$p"
    printf '1\n' > "$t/$name-check.exit"
    : > "$t/$name-check.stdout"
    case "$name" in
      missing-import) entry="$t/native-multifile/negative/missing-helper.atto"; reason='import foo does not resolve to a file' ;;
      ambiguous-import) entry="$t/native-multifile/negative/ambiguous-import.atto"; reason='collide is ambiguous in module' ;;
      ownership-conflict) entry="$t/native-multifile/negative/ownership-conflict.atto"; reason='overlapping mutable borrow arguments' ;;
      aliased-module-leak) entry="$t/native-multifile/module/alias-leak.atto"; reason='unknown class transport_contract.Request' ;;
    esac
    printf '%s:1:1: error: %s\n' "$entry" "$reason" > "$t/$name-check.stderr"
  done
  check_cases "$t" "$t" || { reject "valid synthetic matrix rejected"; exit 1; }
  # A source-free fixture exercises the same full verification path used by
  # the guest job, but carries only synthetic bytes, never a compiler binary.
  local demo_sha="0123456789abcdef0123456789abcdef01234567"
  local compiler_sha="abcdef0123456789abcdef0123456789abcdef01"
  local policy_sha="1234567890abcdef1234567890abcdef12345678"
  printf 'synthetic host-only identity bytes\n' > "$t/fake-artifact"
  local hash_receipt
  for hash_receipt in fixture-sources.sha256 compiler-artifacts.sha256 \
          compiler-classes.sha256 runtime-dependencies.sha256; do
    sha256sum "$t/fake-artifact" > "$t/$hash_receipt"
  done
  printf 'status=PASS\npolicy_head=%s\ngithub_run_id=4242\ngithub_run_attempt=1\ngithub_job=java-linked-guest\ndemo_head=%s\ncompiler_head=%s\npositive_cases=3\nnegative_cases=4\n' \
    "$policy_sha" "$demo_sha" "$compiler_sha" > "$t/identity.txt"
  if ! GITHUB_RUN_ID=4242 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=java-linked-guest \
       ATTO_EXPECTED_POLICY_SHA="$policy_sha" \
       verify "$t" "$demo_sha" "$compiler_sha" "$t" >/dev/null; then
    reject 'valid synthetic full receipt rejected'; exit 1
  fi
  local reject_count=0
  printf '\n' >> "$p"
  if check_cases "$t" "$t" >/dev/null 2>&1; then reject 'extra test row accepted'; exit 1; fi
  reject_count=$((reject_count+1))
  sed -i '$d' "$p"
  sed -i 's/linked-function\tcheck\t0\tPASS/linked-function\tcheck\t1\tPASS/' "$p"
  if check_cases "$t" "$t" >/dev/null 2>&1; then reject 'fake success ledger accepted'; exit 1; fi
  reject_count=$((reject_count+1))
  sed -i 's/linked-function\tcheck\t1\tPASS/linked-function\tcheck\t0\tPASS/' "$p"
  printf '124\n' > "$t/ambiguous-import-check.exit"
  if check_cases "$t" "$t" >/dev/null 2>&1; then reject 'timeout exit accepted'; exit 1; fi
  reject_count=$((reject_count+1))
  printf '1\n' > "$t/ambiguous-import-check.exit"
  printf 'noise' >> "$t/ownership-valid-guest.stdout"
  if check_cases "$t" "$t" >/dev/null 2>&1; then reject 'bad stdout accepted'; exit 1; fi
  reject_count=$((reject_count+1))
  printf '42' > "$t/ownership-valid-guest.stdout"
  printf 'java.lang.RuntimeException\n' > "$t/missing-import-check.stderr"
  if check_cases "$t" "$t" >/dev/null 2>&1; then reject 'JVM exception accepted'; exit 1; fi
  reject_count=$((reject_count+1))
  # Mutate identity and attestations independently of case contents.
  sed -i 's/github_run_attempt=1/github_run_attempt=2/' "$t/identity.txt"
  if GITHUB_RUN_ID=4242 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=java-linked-guest \
       ATTO_EXPECTED_POLICY_SHA="$policy_sha" \
       verify "$t" "$demo_sha" "$compiler_sha" "$t" >/dev/null 2>&1; then
    reject 'stale job attempt accepted'; exit 1
  fi
  reject_count=$((reject_count+1))
  sed -i 's/github_run_attempt=2/github_run_attempt=1/' "$t/identity.txt"
  if GITHUB_RUN_ID=4242 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=source-free-admission \
       ATTO_EXPECTED_POLICY_SHA="$policy_sha" \
       verify "$t" "$demo_sha" "$compiler_sha" "$t" >/dev/null 2>&1; then
    reject 'wrong CI job accepted'; exit 1
  fi
  reject_count=$((reject_count+1))
  if GITHUB_RUN_ID=4242 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=java-linked-guest \
       ATTO_EXPECTED_POLICY_SHA="$compiler_sha" \
       verify "$t" "$demo_sha" "$compiler_sha" "$t" >/dev/null 2>&1; then
    reject 'wrong policy SHA accepted'; exit 1
  fi
  reject_count=$((reject_count+1))
  printf 'tamper' >> "$t/fake-artifact"
  if GITHUB_RUN_ID=4242 GITHUB_RUN_ATTEMPT=1 GITHUB_JOB=java-linked-guest \
       ATTO_EXPECTED_POLICY_SHA="$policy_sha" \
       verify "$t" "$demo_sha" "$compiler_sha" "$t" >/dev/null 2>&1; then
    reject 'tampered hash receipt accepted'; exit 1
  fi
  reject_count=$((reject_count+1))
  echo "ATTO_OWNED_MATRIX_RECEIPT_HOST_SELF_TEST_PASS accepts=1 rejects=$reject_count NOT_EXECUTED"
}

case "${1:-}" in
  --self-test) (( $# == 1 )) || exit 64; self_test ;;
  --verify) (( $# == 5 )) || exit 64; verify "$2" "$3" "$4" "$5" ;;
  *) echo 'usage: verify-atto-owned-matrix.sh --self-test | --verify RECEIPTS DEMO_SHA COMPILER_SHA DEMO_DIR' >&2; exit 64 ;;
esac
