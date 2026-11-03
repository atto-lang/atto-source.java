#!/usr/bin/env bash
# Source-free adversarial test for the receipt gate, NOT guest compiler execution.
set -euo pipefail
src=$(cd "$(dirname "$0")" && pwd)
tmpdir=$(mktemp -d)
trap 'rm -rf -- "$tmpdir"' EXIT
mkdir -p "$tmpdir/work/scripts" "$tmpdir/work/examples" "$tmpdir/bin"
cp "$src/run-replay-video-smoke.sh" "$tmpdir/work/scripts/run-replay-video-smoke.sh"
printf '// source-free stub fixture\n' > "$tmpdir/work/examples/replay-video-disconnect-smoke.atto"
cat > "$tmpdir/bin/mvn" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *package*) if [[ "$CASE" == build_fail ]]; then exit 12; fi; exit 0 ;;
  *exec:java*)
    if [[ "$*" == *"-Dexec.args=--check examples/replay-video-disconnect-smoke.atto"* ]]; then
      if [[ "$CASE" == typecheck_fail ]]; then exit 23; fi
      if [[ "$CASE" == check_stdout_receipt ]]; then echo 'VIDEO-REPLAY-PASS|capacity'; fi
      if [[ "$CASE" == check_stderr_receipt ]]; then echo 'warning: VIDEO-REPLAY-SMOKE-DONE' >&2; fi
      exit 0
    fi
    [[ "$*" == *"-Dexec.args=examples/replay-video-disconnect-smoke.atto"* ]] || exit 22
    ;;
  *) exit 19 ;;
esac
lines=$(cat <<'EXPECTED'
VIDEO-REPLAY-PASS|capacity
VIDEO-REPLAY-PASS|first-chunk
VIDEO-REPLAY-PASS|second-chunk
VIDEO-REPLAY-PASS|sent
VIDEO-REPLAY-PASS|receiver-committed
VIDEO-REPLAY-PASS|resume-missing
VIDEO-REPLAY-PASS|stale-generation-denied
VIDEO-REPLAY-SMOKE-DONE
EXPECTED
)
case "$CASE" in
  wrong_order) printf '%s\n' "$lines" | awk 'NR==1 {a=$0;next} NR==2 {print;print a;next} {print}' ;;
  missing) printf '%s\n' "$lines" | sed '/^VIDEO-REPLAY-PASS|sent$/d' ;;
  success|duplicate|extra|fail_marker|embedded_fail|embedded_pass|invalid_marker|runtime_fail|typecheck_fail|check_stdout_receipt|check_stderr_receipt|stderr_fail|stderr_embedded_fail)
    printf '%s\n' "$lines" ;;
  *) exit 15 ;;
esac
case "$CASE" in
  success|wrong_order|missing) ;;
  duplicate) printf '%s\n' 'VIDEO-REPLAY-PASS|capacity' ;;
  extra) printf '%s\n' 'VIDEO-REPLAY-PASS|new-assertion' ;;
  fail_marker) printf '%s\n' 'VIDEO-REPLAY-FAIL|payload' ;;
  embedded_fail) printf '%s\n' 'mvn: VIDEO-REPLAY-FAIL|payload' ;;
  embedded_pass) printf '%s\n' 'mvn: VIDEO-REPLAY-PASS|payload' ;;
  invalid_marker) printf '%s\n' 'VIDEO-REPLAY-PASS|bad extra text' ;;
  runtime_fail) exit 14 ;;
  typecheck_fail|check_stdout_receipt|check_stderr_receipt) ;;
  stderr_fail) printf '%s\n' 'VIDEO-REPLAY-FAIL|decoder' >&2 ;;
  stderr_embedded_fail) printf '%s\n' 'guest: VIDEO-REPLAY-FAIL|decoder' >&2 ;;
esac
MOCK
chmod +x "$tmpdir/bin/mvn"
run_case() {
  local name=$1 expected=$2 status=0
  (cd "$tmpdir/work" && PATH="$tmpdir/bin:$PATH" CASE="$name" bash scripts/run-replay-video-smoke.sh >"$tmpdir/$name.log" 2>&1) || status=$?
  if [[ "$expected" == success && "$status" != 0 ]] || [[ "$expected" == failure && "$status" == 0 ]]; then
    echo "FAILED receipt-gate selftest: $name (exit $status)" >&2
    cat "$tmpdir/$name.log" >&2
    exit 1
  fi
  if [[ "$expected" == success ]]; then
    grep -Fqx 'JAVA_GUEST_VIDEO_REPLAY_SMOKE_PASS (7/7 actual guest receipts)' "$tmpdir/$name.log"
  elif grep -q 'JAVA_GUEST_VIDEO_REPLAY_SMOKE_PASS' "$tmpdir/$name.log"; then
    echo "Unexpected positive attestation on $name" >&2
    exit 1
  fi
  printf 'RECEIPT-GATE-SELFTEST-PASS|%s\n' "$name"
}
run_case success success
run_case build_fail failure
run_case wrong_order failure
run_case missing failure
run_case duplicate failure
run_case extra failure
run_case fail_marker failure
run_case embedded_fail failure
run_case embedded_pass failure
run_case invalid_marker failure
run_case runtime_fail failure
run_case typecheck_fail failure
run_case check_stdout_receipt failure
run_case check_stderr_receipt failure
run_case stderr_fail failure
run_case stderr_embedded_fail failure
printf '%s\n' 'RECEIPT-GATE-SELFTEST-DONE|16'
