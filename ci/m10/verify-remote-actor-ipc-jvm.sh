#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
expected="${ATTO_EXPECTED_HEAD_SHA:-}"
if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]] || [[ "$(git rev-parse HEAD)" != "$expected" ]]; then
  echo 'M10_NOT_EXECUTED: exact Git source head missing or mismatched' >&2; exit 2
fi
git diff --quiet HEAD -- || { echo 'M10_NOT_EXECUTED: dirty source tree' >&2; exit 2; }
test -z "$(git ls-files --others --exclude-standard)" || {
  echo 'M10_NOT_EXECUTED: unexpected untracked source files' >&2; exit 2;
}
: "${JAVA_HOME:?JDK 21+ must be configured}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/generated"
"$JAVA_HOME/bin/javac" -version
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all,-try -Werror -d "$work/classes" \
  src/m6/AttoM6Runtime.java \
  src/m10/AttoM10TrustedScalarProxy.java \
  src/compiler/m10/AttoM10RestrictedActorCompiler.java \
  src/compiler/m10/AttoM10RestrictedSenderCompiler.java
"$JAVA_HOME/bin/java" -cp "$work/classes" AttoM10RestrictedActorCompiler \
  fixtures/m10/remote_adder.atto "$work/generated/M10GeneratedActor.java"
"$JAVA_HOME/bin/java" -cp "$work/classes" AttoM10RestrictedSenderCompiler \
  fixtures/m10/remote_adder_sender.atto "$work/generated/M10GeneratedSender.java"
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all,-try -Werror -cp "$work/classes" -d "$work/classes" \
  src/m10/AttoM10ProxyIpcGateway.java \
  "$work/generated/M10GeneratedActor.java" \
  "$work/generated/M10GeneratedSender.java" \
  tests/m10/M10RestrictedSenderCompilerProbe.java \
  tests/m10/M10MailboxTurnProbe.java \
  tests/m10/M10RemoteActorProcessProbe.java
"$JAVA_HOME/bin/javac" --release 21 -Xlint:all,-try -Werror -cp "$work/classes" -d "$work/classes" \
  tests/m6/M6NioRuntimeProbe.java \
  tests/m6/M6SelectorLifecycleAuditProbe.java \
  tests/m6/M6ControlBudgetStressProbe.java \
  tests/m6/M6PerMessageTicketProbe.java
timeout 120 "$JAVA_HOME/bin/java" -cp "$work/classes" M6NioRuntimeProbe | tee "$work/m6.log"
grep -Fx 'M6_NIO_SCHEDULER_SUPERVISOR_NATIVE_HOST=PASS' "$work/m6.log" >/dev/null
timeout 90 "$JAVA_HOME/bin/java" -cp "$work/classes" M6PerMessageTicketProbe | tee "$work/tickets.log"
grep -Fx 'M6_TICKET_128_CONCURRENT_MAIL_EXACT_CORRELATION=PASS' "$work/tickets.log" >/dev/null
grep -Fx 'M6_TICKET_PANIC_ABANDON_CONTROL_REINCARNATION=PASS' "$work/tickets.log" >/dev/null
timeout 35 "$JAVA_HOME/bin/java" -cp "$work/classes" M10RestrictedSenderCompilerProbe \
  fixtures/m10/remote_adder_sender.atto | tee "$work/sender.log"
grep -Fx 'M10_COMPILED_RESTRICTED_ATTO_SENDER_BROKER_AND_NEGATIVES=PASS' "$work/sender.log" >/dev/null
timeout 35 "$JAVA_HOME/bin/java" -cp "$work/classes" M10MailboxTurnProbe | tee "$work/mailbox.log"
timeout 35 "$JAVA_HOME/bin/java" -cp "$work/classes" M10RemoteActorProcessProbe | tee "$work/process.log"
for marker in \
  M10_ATOMIC_MAILBOX_ACK_PROCESSED_FAILED_ABANDONED=PASS \
  M10_PANIC_CONTROL_AND_REINCARNATION_FENCE=PASS; do
    grep -Fx "$marker" "$work/mailbox.log" >/dev/null
done
for marker in \
  M10_TWO_REAL_JVM_PROCESSES_UDS_IPC=PASS \
  M10_RESTRICTED_COMPILED_ATTO_REMOTE_RECEIVE=PASS \
  M10_RESTRICTED_COMPILED_ATTO_PROCESS_A_SENDER=PASS \
  M10_REMOTE_RECIPIENT_ADMITTED_AND_PROCESSED=PASS \
  M10_REMOTE_DUPLICATE_FAULT_AND_RESTART_FENCING=PASS \
  M10_12_CONCURRENT_PER_MAIL_SCOPED_RESULTS_OR_VERIFIED_FULL=PASS \
  M10_SCOPE=RESTRICTED_ATTO_GUEST_JAVA_HOST_PROTOCOL_NOT_CANONICAL_BACKEND_OR_TLS; do
    grep -Fx "$marker" "$work/process.log" >/dev/null
done
echo "M10_TESTED_SOURCE_HEAD=$expected"
echo M10_TWO_PROCESS_RESTRICTED_ATTO_GUEST_JVM_PILOT=PASS
