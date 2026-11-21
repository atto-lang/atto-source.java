# Actor CONTROL-plane contract v2

Status: normative hardening target. Host substrate exists in the Java supervisor
stack; full guest/compiler/native parity remains a release gate.

## Three authority classes

Oreslang actor scheduling distinguishes these classes. They are not aliases for
one priority integer.

1. **KERNEL_CONTROL**
   - STOP/CANCEL fencing, terminal-state publication, incarnation retirement,
     scheduler wakeups, runtime shutdown and resource revocation.
   - Runtime-only and non-forgeable.
   - Must not execute arbitrary guest or host policy callbacks.
   - Has independently reserved admission state and execution progress.
2. **SUPERVISOR_CONTROL**
   - on_panic/terminal policy, restart/stop/escalate decisions and structured
     family recovery.
   - Consumes immutable authenticated terminal state published by
     KERNEL_CONTROL.
   - May suspend cooperatively; one supervisor policy turn is logically
     single-flight.
   - Must not share its only physical progress capacity with ordinary actor
     DATA work.
3. **DATA**
   - Permission-checked URGENT plus bounded NORMAL and BULK actor messages.
   - Never convertible into either CONTROL class.

Actor DATA saturation must not consume KERNEL_CONTROL admission, memory credit
or its guaranteed ability to schedule. SUPERVISOR_CONTROL saturation must not
prevent KERNEL_CONTROL from recording or enforcing cancellation/termination.

## Terminal state is durable state, not queue payload

For each supervisor-owned live actor, the runtime reserves one bounded terminal
slot **before** lifecycle ownership becomes visible. The bound is tied to the
maximum managed actor count.

A terminal transition writes exactly one immutable record into the pre-reserved
slot:

    (supervisor_id, group_id, actor_id, incarnation,
     terminal_sequence, spawned_by, kind, failure_code, diagnostic)

The slot remains authoritative until the exclusive supervisor consumer
acknowledges a lifecycle decision or explicitly acknowledges observation.
Notification queues and scheduler tasks are only wakeups. Losing/rejecting a
wakeup must never erase the terminal record.

Consequences:

- a slow/failed supervisor backpressures **new actor admission** rather than
  dropping the identity of an already-admitted failed actor;
- duplicate terminal publication for one incarnation fails closed;
- terminal sequence is monotonic within the supervisor domain;
- transfer of a live actor releases the old reservation only after structured
  ownership and CONTROL ownership are atomically detached;
- normal completion releases an unused reservation;
- failure/cancellation retains the reservation through supervisor policy
  acknowledgement;
- a dispatcher or policy failure retains exact bounded terminal state for
  trusted inspection/escalation.

## Decision commit

Supervisor policy is two-phase:

    terminal observed -> policy awaits/computes -> decision commit -> ACK

RESTART, STOP, ESCALATE and ACKNOWLEDGE are requests until the decision sink
commits them under the lifecycle decision fence. Concurrent supervisor close
must linearize entirely before or after that commit; it may not run between
validation and the lifecycle side effect.

An unresolved/closed/failed supervisor completes its acknowledgement
exceptionally. The parent terminal slot therefore remains durable and cannot be
misreported as handled.

## Progress and boundedness

Separate storage is insufficient without separate execution progress.

- KERNEL_CONTROL must always retain runnable capacity that DATA cannot borrow.
- SUPERVISOR_CONTROL policy execution must be isolated from PRIVATE, SHARED and
  UNTRUSTED actor pools.
- Arbitrary blocking callbacks are not permitted on KERNEL_CONTROL.
- Cooperative supervisor suspension releases physical policy carriers while
  retaining the logical decision lease.
- All queues, reservations, waiters and in-flight callbacks have finite item
  and byte accounting.
- USER URGENT uses bounded quotas/aging; it never outranks KERNEL_CONTROL.

A finite executor cannot guarantee progress if arbitrary callbacks can occupy
all of its carriers forever. Therefore final scheduler integration must either
(a) keep KERNEL_CONTROL on a distinct non-callback execution class, or
(b) prove all work admitted to its reserved carrier is bounded/nonblocking.
Merely increasing CONTROL thread count is not a correctness proof.

## Remote supervision

A remote ProxyActor cannot create CONTROL by serializing a user message.
Remote lifecycle propagation must authenticate supervisor identity, actor
identity and incarnation, reject replay/stale generations, and be idempotent by
terminal sequence. Transport disconnection changes wakeup availability, not
the authoritative local terminal state.

## Required adversarial gates

Cross-backend release requires tests for:

- saturated URGENT/NORMAL/BULK mailboxes while STOP/CANCEL and terminal
  publication still progress;
- all PRIVATE/SHARED/UNTRUSTED actor carriers occupied while supervisor
  terminal state remains observable;
- supervisor policy suspension while an independent supervisor progresses;
- terminal reservation exhaustion backpressuring new spawn/manage admission;
- dispatcher rejection and policy failure retaining exact terminal records;
- close vs awaited policy completion and close vs decision-commit races;
- actor transfer vs child spawn/finalization;
- duplicate/replayed/stale terminal publication;
- restart storms and supervisor-tree escalation under bounded memory;
- remote disconnect/reconnect/replay with incarnation fencing;
- JVM/Graal AOT/hybrid and LLVM/native parity, including sanitizer/model runs.

No backend may claim conformance from parser acceptance, a host-only mock, or a
workflow that executed zero job steps.
