# Queue source guest authorization bridge — draft compiler contract

This branch is stacked on runtime authority PR #2380 (which is stacked
on actor-state PR #760). It adds the actor-only source intrinsic

    self.queue_authorized(int opcode, int producer_slot): bool

under both supported method-call lowering paths. The checker admits it
only on actor self, with exactly two int arguments and no generic type
parameters. It returns a boolean, **not** a Java QueueCommandAuthority,
an ActorId, or a guest-constructible supervisor capability.

## Command kind mapping

0 ACTIVATE, 1 SEND, 2 CANCEL, 3 RECEIVE, 4 CLOSE,
5 TERMINAL_ACK, 6 RETIRE. Unknown opcodes return false.

The runtime computes authorization using the current actor turn's
admitted ActorInboxMail sender and a structured-parent supervisor
binding. Producer SEND/CANCEL additionally require a live granted
producer actor ID matching the requested slot. Source-supplied
producer_id, request_id or epoch cannot authenticate a sender.
If no actor parent, no active mail, wrong runtime context, invalid
opcode or no grant is present, the call fails closed.

## Deliberate limits

This API **checks authority only**. It does not mint producer grants
from source code, guarantee request identity/cancellation semantics,
reserve output slots, ensure failure-atomic completion, or prove
supervisor-controlled actor shutdown.

The queue draft PR #22 uses it as a guard, but it still replies
unauthorized (11) or authorized-but-disabled (12). It does not enable
payload admission or clear the native queue production gate.

QueueGuestAuthorityBridgeTest provides a guest-run denial fixture for
host-origin messages and compiler-negative tests for malformed calls.
The tests have been authored but **not executed** on JDK25 or GraalVM.
Source organization Actions runs are currently zero-step failures.
The private alternate carrier passed a source-free canary, but the
exact source mirror was blocked and no Java source was transferred.

Release gate: compiler-backed positive and negative source execution
against a reviewed exact compiler head, authenticated producer grant
and revocation tests, and crash/backpressure completion guarantees.
All five backends must execute independent conformance before parity.
