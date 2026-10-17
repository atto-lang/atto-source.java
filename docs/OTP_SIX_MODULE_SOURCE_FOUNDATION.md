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
