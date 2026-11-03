#!/usr/bin/env bash
# Executes the actual Oreslang guest via the Java/Graal launcher.
# Do NOT replace this with a host-model test or claim success on zero-step CI.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
if ! command -v mvn >/dev/null 2>&1; then
  echo "UNCONFIGURED: Maven is required to build and execute the guest" >&2
  exit 2
fi
if ! command -v java >/dev/null 2>&1; then
  echo "UNCONFIGURED: Java/GraalVM is required" >&2
  exit 2
fi

if [[ ! -f examples/replay-video-disconnect-smoke.ores ]]; then
  echo 'UNCONFIGURED: guest source file is missing' >&2
  exit 2
fi

tmpdir=$(mktemp -d)
trap 'rm -rf -- "$tmpdir"' EXIT
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
# The exact seven receipts must be emitted in order by the real guest.
# Reject malformed, embedded, extra, duplicated or explicit FAIL markers.
# Maven's unrelated informational output is allowed, but never a spoofed marker.
if ! LC_ALL=C awk '
  /VIDEO-REPLAY-/ {
    if ($0 ~ /^VIDEO-REPLAY-PASS\|[A-Za-z0-9-]+$/ || $0 == "VIDEO-REPLAY-SMOKE-DONE") {
      print
    } else {
      invalid = 1
    }
  }
  END { if (invalid) exit 1 }
' "$tmpdir/guest.log" >"$tmpdir/observed"; then
  cat "$tmpdir/guest.log" >&2
  echo "MALFORMED_GUEST_RECEIPT" >&2
  exit 1
fi
if ! diff -u "$tmpdir/expected" "$tmpdir/observed"; then
  echo "GUEST_RECEIPT_MISMATCH" >&2
  exit 1
fi
echo "JAVA_GUEST_VIDEO_REPLAY_SMOKE_PASS (7/7 actual guest receipts)"
