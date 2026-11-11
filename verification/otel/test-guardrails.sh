#!/usr/bin/env bash
# SOURCE-FREE harness-control tests. Fake class bytes and harmless shell
# commands below NEVER count as Oreslang compilation or guest execution.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
# shellcheck source=verification/otel/guardrails.sh
source "$root/verification/otel/guardrails.sh"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

fail() { echo "OTEL_GUARDRAIL_TEST_FAILURE: $*" >&2; exit 1; }
classes="$work/classes"
mkdir -p "$classes/dev/oreslang/launcher"
printf 'class-v1' > "$classes/dev/oreslang/launcher/OresMain.class"
initial="$(otel_guardrails_class_digest "$classes")" || fail 'class manifest should hash'
[[ "$initial" =~ ^[0-9a-f]{64}$ ]] || fail 'invalid SHA256 class digest'
printf 'class-v2' > "$classes/dev/oreslang/launcher/OresMain.class"
next="$(otel_guardrails_class_digest "$classes")" || fail 'updated class manifest should hash'
[[ "$initial" != "$next" ]] || fail 'changed class bytes not detected'

ln -s /dev/null "$classes/dev/oreslang/launcher/forged.class"
if otel_guardrails_class_digest "$classes" >/dev/null 2>&1; then
  fail 'class tree symbolic link accepted'
fi
rm -f "$classes/dev/oreslang/launcher/forged.class"
ln -s "$classes" "$work/symlink-classes"
if otel_guardrails_class_digest "$work/symlink-classes" >/dev/null 2>&1; then
  fail 'symbolic class-root accepted'
fi

output="$work/capture"
mkdir -p "$output"
# Only exercise bounded launch mechanics with an ordinary shell command; NOT
# a real AttoLang/Oreslang guest and not a success receipt for compiler parity.
otel_guardrails_run_guest "$output" /bin/bash -c 'printf "safe-smoke\n"; printf "diagnostic\n" >&2' ||
  fail 'bounded subprocess control rejected harmless stdout'
[[ "$(cat "$output/guest.stdout.jsonl")" == 'safe-smoke' ]] ||
  fail 'stdout capture mismatched'
[[ "$(cat "$output/guest.stderr.txt")" == 'diagnostic' ]] ||
  fail 'stderr capture mismatched'

# Stale success output must be cleared even when the next process fails.
if otel_guardrails_run_guest "$output" /bin/bash -c 'exit 7'; then
  fail 'nonzero real process exit accepted'
fi
[[ ! -s "$output/guest.stdout.jsonl" ]] ||
  fail 'stale successful stdout survived failed subprocess'

# Linux 1 MiB file-size RLIMIT must prevent unbounded stdout on a noisy guest.
if otel_guardrails_run_guest "$output" /bin/bash -c 'head -c 2097152 /dev/zero' 2> "$work/flood-control.log"; then
  fail 'oversized stdout process admitted as success'
fi
[[ "$(wc -c < "$output/guest.stdout.jsonl")" -le 1048576 ]] ||
  fail 'stdout filesystem byte quota failed'
if otel_guardrails_run_guest "$output" /bin/bash -c 'head -c 2097152 /dev/zero >&2' 2> "$work/flood-control.log"; then
  fail 'oversized stderr process admitted as success'
fi
[[ "$(wc -c < "$output/guest.stderr.txt")" -le 1048576 ]] ||
  fail 'stderr filesystem byte quota failed'

ln -s "$output" "$work/capture-link"
if otel_guardrails_run_guest "$work/capture-link" /bin/true; then
  fail 'symlink output directory admitted'
fi
if otel_guardrails_run_guest "$output"; then
  fail 'empty guest command admitted'
fi
echo 'OTEL_SOURCE_ONLY_GUARDRAILS_PASS (class tamper, symlinks, bounded output, nonzero failure)'
echo 'NO_COMPILER_GUEST_EXECUTED_OR_CERTIFIED'
