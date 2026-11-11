# Actor mailbox channels: immediate vs awaitable admission (v1)

**Status:** Java/GraalVM source/runtime draft implementation, not five-backend
conformance. The underlying mailbox transport is a `Channel<T>`-based
actor-owned runtime channel; actor messages are validated and snapped before
channel admission. Never expose a raw actor inbox channel to guest code.

## Channel operations

| Source expression | Immediate completion | When full or no rendezvous reader | Result |
| --- | --- | --- | --- |
| `writech channel, value` | yes | Suspend logical task/continuation until admitted | `void` |
| `nb writech channel, value` | yes | Register a bounded writer; return without suspending caller | `Future<void>` |
| `try writech channel, value` | yes | Return `false`; register **nothing** | `bool` |
| `readch channel` | yes | Suspend logical task until a value arrives | `T` |
| `nb readch channel` | yes | Return a bounded pending read Future | `Future<T>` |
| `try readch channel` | yes | Return `None`; register **nothing** | `Option<T>` |

"Blocking" is **cooperative actor suspension**, not OS-thread parking,
spin-waiting or blocking I/O. Carrier threads must remain available.
Capacity 0 is a rendezvous channel, not an unbounded channel.

## ActorRef messaging

| Source expression | When USER mailbox full | Result |
| --- | --- | --- |
| `worker.send(value)` (legacy) | Immediately raises typed `MAILBOX_FULL` | `void` |
| `worker.try_send(value)` | Immediately returns `false`; **no** waiter | `bool` |
| `worker.send_async(value)` | Returns a `Future`; bounded producer retention | `Future<void>` |
| `await worker.send_async(value)` | Suspends current actor task until admission | `void` |

A successful send means **atomic admission to the destination mailbox**,
not execution, reply or durability. Use `get_done`, `drain`, or explicit
ack/reply semantics as appropriate to observe later milestones.

`try_send` reports `false` **only** for a full USER queue. Invalid/stale
references, stop/seal, authorization errors, invalid payloads, and unsupported
serialization are *errors*, never disguised as capacity. Successful probes
reserve and publish exactly once. On failure no delayed delivery is possible.
The source language must reject capturing actor-ref send methods as
unvalidated first-class callables; they remain direct-call-only.

`send_async` must snapshot/validate the payload at invocation time and
retain it only within finite actor-owned producer credits. No second
unbounded queue; no externally mutable aliases. Cancellation before
admission must remove the registration and refund all quotas. A winning
admission cannot be retracted by later cancellation. Full self-mailbox
awaits must fail closed instead of deadlocking the actor's own turn.

## Supervisor and runtime CONTROL

Mailboxes remain actor-owned channels, but DATA, task continuations and
supervisor CONTROL use distinct admission/authority lanes. User code must
not forge or enqueue CONTROL or turn supervisor commands into ordinary
user `try_send` traffic. STOP/CANCEL/panic/supervisor wakeups have separately
reserved progress, even under complete USER saturation. Priority does not
permit concurrent guest execution in a single actor.

## Serialization boundary

The canonical typed wire is the bounded, schema-guided MessagePack v2
envelope. Local in-process channels may carry immutable native snapshots,
or transfer owned payloads via a proven `rt take` optimization, **only**
if value, error, ownership, isolation and admission behavior is observably
equivalent to the canonical wire semantics. No raw writable references,
unsandboxed pointers or unversioned ActorRef tokens cross data isolation
boundaries; trusted runtime CONTROL capabilities have a separate audited
path.

## Release gates

- Exact-source parse/typecheck/guest execution for both ActorRef send modes.
- Saturated bounded channel and mailbox: immediate false, no phantom mail,
  `nb`/async waiter later commits with proper FIFO and quota accounting.
- Concurrent try-send races: at most capacity credits; no duplicate deliveries.
- Cancellation/STOP/restart concurrent with waiting writes and supervisor
  CONTROL progress; no actor-carrier parking, no self-send deadlock.
- Checked ownership snapshots, security errors distinct from backpressure.
- LLVM, .NET, Forge, WASM source-lowering and runtime conformance with identical
  scenarios. A standalone byte queue or codec is not proof of source parity.

This Java draft intentionally does not claim that the native/backend
implementations or MessagePack source paths have passed those gates.
