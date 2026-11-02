#!/usr/bin/env bash
# Executes the actual Oreslang guest via the Java/Graal launcher.
# Do NOT replace this with a host-model test or claim success on zero-step CI.
set -euo pipefail

cd "$(dirname "$0")/.."
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNCONFIGURED: Maven is required to build and execute the guest" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "UNCONFIGURED: Java/GraalVM is required" >&2
  exit 2
fi

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
cat >"$tmpdir/expected" <<'RECEIPTS'
VIDEO-REPLAY-PASS|capacity
VIDEO-REPLAY-PASS|first-chunk
VIDEO-REPLAY-PASS|second-chunk
VIDEO-REPLAY-PASS|sent
VIDEO-REPLAY-PASS|receiver-committed
VIDEO-REPLAY-PASS|resume-missing
VIDEO-REPLAY-PASS|stale-generation-denied
VIDEO-REPLAY-SMOKE-DONE
RECEIPTS

# Build compiler/launcher first; Maven returning a zero exit by itself proves nothing.
mvn -B -ntp -DskipTests package >"$tmpdir/build.log" 2>&1 || {
  cat "$tmpdir/build.log" >&2
  echo "COMPILER_BUILD_FAILED" >&2
  exit 1
}
mvn -B -ntp -q -DskipTests exec:java \
  -Dexec.args="examples/replay-video-disconnect-smoke.ores" \
  >"$tmpdir/guest.log" 2>"$tmpdir/guest.err" || {
  cat "$tmpdir/guest.log" >&2
  cat "$tmpdir/guest.err" >&2
  echo "GUEST_EXECUTION_FAILED" >&2
  exit 1
}
# The guest's 7 distinct PASS receipts and terminal DONE must appear in exact order.
# A nonzero runtime exit or missing/extra/duplicated/out-of-order/FAIL receipts fails.
grep -E '^VIDEO-REPLAY-(PASS|FAIL)\||^VIDEO-REPLAY-SMOKE-DONE$' \
  "$tmpdir/guest.log" >"$tmpdir/observed" || {
  cat "$tmpdir/guest.log" >&2
  echo "NO_GUEST_RECEIPTS" >&2
  exit 1
}
diff -u "$tmpdir/expected" "$tmpdir/observed" || {
  echo "GUEST_RECEIPT_MISMATCH" >&2
  exit 1
}
echo "JAVA_GUEST_VIDEO_REPLAY_SMOKE_PASS (7/7 actual guest receipts)"
