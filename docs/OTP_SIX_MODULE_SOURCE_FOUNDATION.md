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
