# Six-module Oreslang OTP source foundation

This stacked branch establishes executable Oreslang source files for the exact
six-module v1 inventory:

- `stdlib/otp/gen_server.ores`
- `stdlib/otp/supervisor.ores`
- `stdlib/otp/gen_statem.ores`
- `stdlib/otp/application.ores`
- `stdlib/otp/event_manager.ores`
- `stdlib/otp/registry.ores`

These are deliberately **bounded source/protocol slices**, not a claim of full
Erlang/OTP parity.

## What is real in source today

- GenServer: typed init/call/cast/info callback result envelopes and bounded
  pending-call policy.
- Supervisor: restart range selection, child restart classification and bounded
  restart-budget predicate.
- GenStateMachine: typed state/data transitions, defer/stop outcomes and bounded
  timer-slot validation.
- Application: dependency-readiness and reverse-rollback range policy.
- EventManager: typed handler keep/remove outcomes and bounded subscription
  admission.
- Registry: typed entries, positive incarnation fencing and bounded registry
  admission.

All six modules are explicitly admitted by the closed packaged-source registry;
unknown `std/otp/*` imports still reject. Tests assert the exact inventory and
scan each module for host/OS effects.

## What remains runtime authority

Library source must not manufacture or emulate:

- actor lifecycle or supervisor CONTROL authority;
- opaque one-shot GenServer reply capabilities;
- mailbox reservation or delivery;
- scheduler threads/carriers;
- monotonic clocks/timer delivery;
- mutable global registry storage;
- event handler queues;
- filesystem/network/process services;
- distributed naming/storage;
- hot-code generation activation.

Those capabilities are supplied by the target runtime. A bare-metal actor
runtime may provide fixed slots/arenas and a static scheduler without Linux.

## Evidence labels

Until tests execute on the exact head:

- source files present: yes;
- static resolver registration: yes;
- runtime guest execution: **unverified**;
- full OTP behaviors: no;
- LLVM parity: no;
- bare-metal actor execution: no.

Focused gate:

```sh
mvn -q -Dtest=NativeOtpBehaviorSlicesTest,NativeOtpGenServerProtocolTest,NativeOtpSupervisorPolicyTest test
```

## Admission and monotonic-time hardening (2026-10-17)

The source layer now also offers three optional pure policy helpers:

- `GenServer.pending_capacity_allows(queued, in_flight, reserved, capacity)`:
  checks the *combined* outstanding-call reservation budget, avoiding integer
  addition overflow and detecting internally inconsistent accounting.
- `EventManager.delivery_capacity_allows(queued, in_flight, reserved, capacity)`:
  keeps delivery/mailbox accounting **separate** from subscriber count.
- `GenStateMachine.timer_due(previous_tick, current_tick, deadline_tick)`:
  compares explicit monotonic samples and rejects clock rollback. Deadline
  equality means due. No clock, timer, or I/O capability is introduced.

These helpers are `pure decision functions`, not authoritative mailbox or
timer operations. They do not reserve slots, cancel pending calls, prevent
races, or mint capabilities. A real provider must atomically perform admission
and check unforgeable CONTROL/actor identities. The older two-argument
GenServer helper assumes its `pending` counter covers *all* outstanding
credits and is retained for compatibility, not as a queue-only shortcut.

New JUnit suite `NativeOtpAdmissionBoundariesTest` exercises guest-source
boundary cases. This is **not a verified pass** until Maven actually runs the
exact tree on a runner. Full OTP remains blocked on runtime integration and
LLVM typed imports. Supervisor group-restart accounting is tracked separately
in Java PR #1233; do not permit group restarts to charge one child to the
intensity budget.

## Further adversarial audit: supervisor amplification, overcapacity and identity

The first source-layer audit found three ways that a policy helper could mislead
an eventual runtime integrator:

1. The supervisor's original two-argument `restart_budget_allows` checks one
   replacement, **not** a whole `one_for_all`/`rest_for_one` restart span.
   A new three-argument version checks the complete requested replacement
   count using subtraction (no overflow), and the old one delegates as a
   single-replacement compatibility helper. The authoritative supervisor must
   atomically charge credits, reject stale CONTROL and exhaust upward, rather
   than reading/checking without committing an update. This ports the core
   idea of sibling draft PR #1233 into this six-module integration branch;
   reconcile the overlapping branch changes before merge.
2. Legacy GenServer, EventManager and Registry one-count capacity checks now
   distinguish `count == capacity` (validly full: `Ok(false)`) from
   `count > capacity` (internally inconsistent: `Err`). This is not
   an atomic reservation or permission to use an untrusted snapshot.
3. Registry `same_incarnation` **must never authorize a lookup**: two
   unrelated actors may share a numeric incarnation. New pure
   `registration_matches(scope, actor, incarnation, ...)` compares the
   entire scoped identity triple and rejects zero/negative IDs. These numbers
   are **not authenticated capabilities**, and callers must only use
   runtime-derived tags; a guest must not mint or assert arbitrary identity.

The existing generic `Entry<Name, TargetRef>` constructor can still store
arbitrary values such as a string in source tests; it is **only a typed
record**, not an ActorRef authenticity proof or a working registry. That gap
must be closed by a runtime-minted, capability-checked actor registration API,
not merely another string/int equality predicate.

`NativeOtpPolicyHardeningTest` adds guest-source cases for all three areas,
including cross-scope, cross-actor, stale incarnation, group restart charging,
no-op budget, invalid windows and inconsistent counts. Keep the suite
**unverified** until the exact source head executes real JUnit steps.
