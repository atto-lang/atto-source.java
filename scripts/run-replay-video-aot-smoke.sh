#!/usr/bin/env bash
# Real GraalVM Native Image guest admission, NOT a host-only parity simulation.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ ! -f examples/replay-video-disconnect-smoke.atto ]]; then
  echo 'UNCONFIGURED: .atto replay smoke source missing' >&2
  exit 2
fi
if [[ ! -x target/ores-aot ]]; then
  echo 'UNCONFIGURED: native-image guest launcher missing' >&2
  exit 2
fi

tmpdir=$(mktemp -d)
trap 'rm -rf -- "$tmpdir"' EXIT
cat > "$tmpdir/expected" <<'RECEIPTS'
VIDEO-REPLAY-PASS|capacity
VIDEO-REPLAY-PASS|first-chunk
VIDEO-REPLAY-PASS|second-chunk
VIDEO-REPLAY-PASS|sent
VIDEO-REPLAY-PASS|receiver-committed
VIDEO-REPLAY-PASS|resume-missing
VIDEO-REPLAY-PASS|stale-generation-denied
VIDEO-REPLAY-SMOKE-DONE
RECEIPTS

# Both compiler validation and guest execution must use the actual AOT image.
./target/ores-aot --mode=aot --platform=linux --check \
  examples/replay-video-disconnect-smoke.atto \
  > "$tmpdir/check.out" 2> "$tmpdir/check.err" || {
  cat "$tmpdir/check.out" "$tmpdir/check.err" >&2
  echo 'AOT_GUEST_COMPILATION_CHECK_FAILED' >&2
  exit 1
}
# A compile-only check may not execute main or emit any guest receipt.
if LC_ALL=C grep -Fq 'VIDEO-REPLAY-' "$tmpdir/check.out" ||
   LC_ALL=C grep -Fq 'VIDEO-REPLAY-' "$tmpdir/check.err"; then
  cat "$tmpdir/check.out" "$tmpdir/check.err" >&2
  echo 'AOT_GUEST_CHECK_EXECUTED_MAIN' >&2
  exit 1
fi
./target/ores-aot --mode=aot --platform=linux \
  examples/replay-video-disconnect-smoke.atto \
  > "$tmpdir/guest.out" 2> "$tmpdir/guest.err" || {
  cat "$tmpdir/guest.out" "$tmpdir/guest.err" >&2
  echo 'AOT_GUEST_EXECUTION_FAILED' >&2
  exit 1
}
# A bare Native Image has no Maven wrapper stdout to discard. Treat every
# extra byte on stdout and any stderr as a failed guest attestation rather
# than silently ignoring unmarked diagnostics or duplicated receipts.
if [[ -s "$tmpdir/guest.err" ]]; then
  cat "$tmpdir/guest.err" >&2
  echo 'AOT_GUEST_UNEXPECTED_STDERR' >&2
  exit 1
fi
if ! diff -u "$tmpdir/expected" "$tmpdir/guest.out"; then
  echo 'AOT_GUEST_RECEIPT_MISMATCH' >&2
  exit 1
fi
printf '%s\n' 'AOT_GUEST_VIDEO_REPLAY_SMOKE_PASS (7/7 actual guest receipts)'
