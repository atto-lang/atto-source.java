#!/usr/bin/env bash
# Source-free test of AOT receipt gate using a fake binary; NOT compiler evidence.
set -euo pipefail
src=$(cd "$(dirname "$0")" && pwd)
tmpdir=$(mktemp -d)
trap 'rm -rf -- "$tmpdir"' EXIT
mkdir -p "$tmpdir/work/scripts" "$tmpdir/work/examples" "$tmpdir/work/target"
cp "$src/run-replay-video-aot-smoke.sh" "$tmpdir/work/scripts/"
printf '// source-free fixture\n' > "$tmpdir/work/examples/replay-video-disconnect-smoke.atto"
cat > "$tmpdir/work/target/ores-aot" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == *'--mode=aot --platform=linux'* ]] || exit 20
[[ "$*" == *'examples/replay-video-disconnect-smoke.atto'* ]] || exit 21
if [[ "$*" == *' --check '* ]]; then
  [[ "$CASE" != check_fail ]] || exit 22
  if [[ "$CASE" == check_stdout_receipt ]]; then echo 'VIDEO-REPLAY-PASS|capacity'; fi
  if [[ "$CASE" == check_stderr_receipt ]]; then echo 'warning: VIDEO-REPLAY-SMOKE-DONE' >&2; fi
  exit 0
fi
lines=$(cat <<'RECEIPTS'
VIDEO-REPLAY-PASS|capacity
VIDEO-REPLAY-PASS|first-chunk
VIDEO-REPLAY-PASS|second-chunk
VIDEO-REPLAY-PASS|sent
VIDEO-REPLAY-PASS|receiver-committed
VIDEO-REPLAY-PASS|resume-missing
VIDEO-REPLAY-PASS|stale-generation-denied
VIDEO-REPLAY-SMOKE-DONE
RECEIPTS
)
case "$CASE" in
  wrong_order) printf '%s\n' "$lines" | awk 'NR==1 {a=$0;next} NR==2 {print; print a;next} {print}' ;;
  missing) printf '%s\n' "$lines" | sed '/^VIDEO-REPLAY-PASS|sent$/d' ;;
  *) printf '%s\n' "$lines" ;;
esac
case "$CASE" in
  success|check_fail|check_stdout_receipt|check_stderr_receipt|wrong_order|missing) ;;
  guest_fail) exit 23 ;;
  duplicate) echo 'VIDEO-REPLAY-PASS|capacity' ;;
  extra) echo 'VIDEO-REPLAY-PASS|unexpected' ;;
  stdout_fail) echo 'VIDEO-REPLAY-FAIL|decoder' ;;
  embedded) echo 'a tool printed VIDEO-REPLAY-PASS|capacity' ;;
  stderr) echo 'VIDEO-REPLAY-FAIL|decoder' >&2 ;;
  stderr_embedded) echo 'diagnostic: VIDEO-REPLAY-FAIL|decoder' >&2 ;;
  stdout_noise) echo 'unexpected runtime progress line' ;;
  stderr_noise) echo 'unexpected runtime warning' >&2 ;;
  stdout_hidden_error) echo 'fatal: unexpected error, no marker' ;;
  *) exit 25 ;;
esac
STUB
chmod +x "$tmpdir/work/target/ores-aot"
run() {
  local name=$1 expected=$2 status=0
  (cd "$tmpdir/work" && CASE="$name" bash scripts/run-replay-video-aot-smoke.sh >"$tmpdir/$name.log" 2>&1) || status=$?
  if [[ "$expected" == success && "$status" != 0 ]] || [[ "$expected" == failure && "$status" == 0 ]]; then
    echo "AOT self-test case $name expected $expected, exit $status" >&2
    cat "$tmpdir/$name.log" >&2
    exit 1
  fi
  if [[ "$expected" == failure ]] && grep -Fq 'AOT_GUEST_VIDEO_REPLAY_SMOKE_PASS' "$tmpdir/$name.log"; then
    echo "invalid positive attestation on $name" >&2
    exit 1
  fi
  printf 'AOT-RECEIPT-SELFTEST-PASS|%s\n' "$name"
}
run success success
run check_fail failure
run check_stdout_receipt failure
run check_stderr_receipt failure
run guest_fail failure
run duplicate failure
run wrong_order failure
run missing failure
run extra failure
run stdout_fail failure
run embedded failure
run stderr failure
run stderr_embedded failure
run stdout_noise failure
run stderr_noise failure
run stdout_hidden_error failure
mv "$tmpdir/work/examples/replay-video-disconnect-smoke.atto" "$tmpdir/source.bak"
run missing_source failure
printf '// source-free fixture\n' > "$tmpdir/work/examples/replay-video-disconnect-smoke.atto"
mv "$tmpdir/work/target/ores-aot" "$tmpdir/aot.bak"
run missing_image failure
printf '%s\n' 'AOT-RECEIPT-SELFTEST-DONE|18'
