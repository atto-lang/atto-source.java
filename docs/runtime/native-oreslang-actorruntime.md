# Native Oreslang actor runtime migration

Status: **implementation in progress — NOT yet the live actor runtime**.

## Ownership decision

The **canonical implementation language** for Oreslang actor semantics is
Oreslang itself. The implementation entrypoint is
[`stdlib/actorruntime.ores`](../../stdlib/actorruntime.ores), not
`src/main/java/dev/oreslang/runtime/ActorRuntime.java`. That Java class is a
temporary bootstrap/reference adapter and must be retired when native Oreslang
can own the full lifecycle.

Do not merely rename Java source to `.ores`, use `java:` imports to call the
old implementation, or introduce a second independent actor scheduler.

The first checked-in source slice is **pure lifecycle policy**, executable as
Oreslang. It does not yet create actors, drive a mailbox, perform STOP cleanup
or implement the dispatcher. No production code should claim native actor
execution solely because these policy functions compile.

## End-state source layout

- `stdlib/actorruntime.ores`: authoritative actor creation, ActorId/incarnation,
  lifecycle, ownership, STOP/force, cleanup-only lane, supervisor CONTROL, DONE,
  state retirement and actor hierarchies
- `stdlib/scheduler.ores`: master scheduler, pool control loops, actor leases,
  runnable queues, fairness, backpressure, admission and park/resume
- `stdlib/future.ores` and `stdlib/channel.ores`: guest semantic ownership
  of Futures, cancellation, select, channel send/receive and stream lifetimes
- `stdlib/mailman.ores`, `stdlib/eventbus.ores`: first-class actor routing
  and event semantics, including pipeline `self.forward()` and
  `self.backward()`
- VM implementations: only sealed low-level intrinsics required to execute the
  language and interact with physical resources; Java/GraalVM and LLVM use
  the **same versioned Oreslang-visible ABI** and shared guest conformance

All source implementation modules above, except `actorruntime.ores`, are
**targets, not a statement that those files already exist**.

## Gate 0 implemented policy, not scheduler authority

The first executable functions are deliberately pure Oreslang. Their inputs are
scalar snapshots of VM-owned state, **not guest permissions**. They cannot
perform mailbox reservation, authenticate a continuation, obtain a physical
carrier lease or retire memory. None of these functions is a replacement for a
VM intrinsic or a security capability.

The initial policy is exercised in
[`examples/native-actorruntime-contract.ores`](../../examples/native-actorruntime-contract.ores)
and in a host-side guest-source execution regression. It covers:

- user-mail refusal after STOP, graceful sealing, force revocation, finalization
  or VM closure;
- bounded admission decisions for one lane plus the aggregate mailbox budget,
  rejecting negative/incoherent counters; **the actual atomic reservation
  remains VM-owned**;
- scheduler continuations preceding URGENT and NORMAL mail, urgent burst
  fairness after eight user messages, and suspension preserving only the
  internal continuation lane;
- authenticated pre-queued SourceTask retention and single-flight terminal
  delivery/cleanup decisions;
- state-retirement barriers including detached STOP releases and outstanding
  source task lifetimes, and ordered DONE publication.

**Lane 1 in the pure arbitration policy is the scheduler continuation lane,
not the supervisor CONTROL channel.** Supervisor CONTROL is a distinct
VM-reserved authenticated route and must never be writable from a guest
`actorruntime.ores` invocation.

The Java/GraalVM backend still executes the live scheduling and cancellation
runtime. A passing Gate 0 means Oreslang-owned policy can be linked/executed;
it does not establish that the live runtime invokes this module or that LLVM
can compile full actors. Gate 1 requires sealed intrinsics with generation-safe
handles and actual .ores-owned actor state transitions.

## VM and trust boundary

To bootstrap an Oreslang program, the VM must exist before any Oreslang module
runs. The VM/backend necessarily performs low-level operations that guest code
cannot safely self-authorize:

1. Verify and enter a VM-controlled actor/heap/execution domain with an opaque
   unforgeable ActorId + incarnation; allocate and revoke physical isolates.
2. Provide atomic, memory-ordered ownership of one actor turn and its bounded
   mailbox and scheduler queue; expose opaque handles, not raw host objects.
3. Save/reinstate heap-owned continuations at compiler-defined suspension
   points; wake a carrier without running guest code on a foreign thread.
4. Perform kernel-facing OS I/O, clock and wake operations with VM-enforced
   capabilities, quotas, and resource ownership.
5. Enter and leave a Truffle/LLVM guest frame and perform final physical
   memory cleanup only when actor leases and owned loans are gone.

These are **intrinsics**, not Java implementations of actor policy. Oreslang
must decide scheduling and lifecycle behavior above this boundary. The current
Java/GraalVM bootstrap is temporary and must eventually call the exact same
intrinsic contracts as LLVM or be reduced to compiler/VM entry glue.

The VM must authenticate provenance before giving `actorruntime.ores` a
`scheduler_provenance_valid` or identity attestation. Passing a guest-supplied
`true` does not grant authority. The current pure policy functions are
suitable for conformance but are **not** privileged security gates.

## Core actor guarantees

- At most **one native carrier** executes an actor at any instant, including
  ordinary receive, terminal cleanup, timers and CONTROL handlers. Multiple
  outstanding logical messages do not imply simultaneous actor code.
- One fixed-key actor-owned `state { ... }` map, never ordinary mutable
  actor instance fields. Read via `self.handle_state(|s| -> ...)` or
  `using state.read(|s| -> ...)`; exclusive writes via
  `self.handle_state(critical |s| -> ...)` or
  `using state.write(critical |s| -> ...)`.
- Signature `with` is the upper bound on effects; `using` acquires a
  lexical state/resource capability. Loans must not escape or survive
  `await`, `select`, cancellation or suspension.
- Source-level `spawn` constructs actor state after the VM installs its
  execution domain. ActorRef interactions are message-only.
- STOP is monotonic: reject user mail, permit only source-scheduler-owned
  terminal continuations, do not replay unstarted frames, and do not resurrect
  stale generation/actor identities.
- A cancellation **request** may authorize an authenticated terminal delivery
  turn; only successful one-shot injection authorizes guest cleanup effects.
  Ordinary guest `catch` cannot swallow terminal unwind. `defer`, `finally`,
  iterator closure, state loans and guards must run exactly once where permitted.
- A bounded cleanup deadline does not license guest execution off-lane or
  physical retirement while a carrier is still in a guest frame. Forced
  physical revocation requires a real VM isolation boundary.
- `get_done()` only settles after lexical cleanup, descendants, final cleanup,
  state retirement, output closure and supervisor terminal publication.
  Host-fatal and untrusted quota failures retain defined dominance.
- Master scheduler plus per-pool dedicated scheduler/control loops. Pool worker
  capacity is 0–5 and carriers activate lazily. All guest scheduling behavior
  is implemented in Oreslang over the sealed host wake/park primitives.

## Incremental acceptance gates

| Gate | Required executable evidence |
| --- | --- |
| 0 — Native policy | `std/actorruntime` links, runs and passes the pure Oreslang lifecycle policy guest fixture; no `java:` host imports |
| 1 — Actor state machine | Implement the actor-owned lifecycle map and STOP transitions in `.ores`; authoritative VM-verified identity, incarnation, cleanup permits; no duplicate Java policy authority |
| 2 — Scheduler and continuations | Source-written actor queues, cleanup-only CONTROL lane, async cancellation and physical turn barriers run through opaque intrinsics; adversarial capacity/races |
| 3 — Structured semantics | Native `spawn`, messages, actor groups, supervisor CONTROL, `with` / `using` critical state loans, `self.forward()` / `self.backward()`, select and channels |
| 4 — Backend parity | Compile and execute **the same** `.ores` guest scenarios on GraalVM JIT, Native Image AOT/hybrid and LLVM; compare traces and terminal failures |
| 5 — Java retirement | Remove Java lifecycle/scheduler policy only after the native path is the one invoked in every supported backend. Retain only minimum VM/compiler adapters and backend tests |

For every gate, compare source root Git tree SHAs and require executed CI steps
instead of accepting zero-step GitHub Action failures as test evidence. Failure
to link/compile a required `.ores` feature must fail closed, never quietly
fall back to a Java policy implementation.

## Relationship to Java PR #823

PR #823 currently exercises the Java bootstrap implementation. Its cancellation,
single-flight and DONE regressions are **migration conformance inputs**, not a
reason to make Java `ActorRuntime` the permanent semantic owner. Keep that
branch draft until the native actor runtime can execute equivalent end-to-end
source scenarios. Any temporary security fix to Java is defensive compatibility
work and does not count toward implementation of the native runtime.
