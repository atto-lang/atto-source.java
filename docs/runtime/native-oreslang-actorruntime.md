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

### STOP arbitration hardening

Selecting a mailbox lane and admitting a physical actor turn are different
operations. The pure `choose_mailbox_lane` now accepts lifecycle state and
a purported retained-cleanup permit so its decision table can be tested:

- **LIVE:** authenticated scheduler continuations have precedence; after eight
  urgent user messages, an already-queued normal message gets an opportunity.
- **GRACEFULLY SEALED:** old user messages remain drainable; sealing is a
  sender-admission condition, *not* a physical STOP.
- **STOPPED:** no queued user message is selected. An internally scheduled
  cleanup continuation is selected only if it is present **and** its retained
  identity/cleanup permit has been attested by the VM.
- **FINALIZED / FORCE REVOKED / VM CLOSED:** no guest turn is selected, even
  when a purported cleanup continuation is present.
- **CORRUPT URGENT BURST COUNT:** a negative value yields an invalid decision
  rather than silently falling through to user execution.

The scalar `retained_cleanup_continuation = true` argument is **not** a
capability. Normal guest callers can supply it themselves; they gain no
privileged actor access. Gate 1 MUST replace these attestations with a sealed
generation-specific handle, validate it again at the atomic dispatch commit,
and reject stale actor/restart/queue identities. A policy result without
that VM check is advisory and never authorizes guest execution.

Admission likewise rejects impossible queue snapshots (negative counts,
lane count greater than aggregate count, or lane capacity greater than
aggregate capacity). This is **not** an atomic quota implementation: both
precheck and credit reservation must occur in one VM-owned synchronization
transaction. If the VM exposes a separate control lane with reserved capacity,
its accounting budget must explicitly include that reserved capacity; no
application can mint CONTROL work by choosing a lane number.

The Java/GraalVM backend still executes the live scheduling and cancellation
runtime. A passing Gate 0 means Oreslang-owned policy can be linked/executed;
it does not establish that the live runtime invokes this module or that LLVM
can compile full actors. Gate 1 requires sealed intrinsics with generation-safe
handles and actual .ores-owned actor state transitions.

## Gate 1 prototype: native actor-turn ownership kernel

The portable native **prototype** under `src/main/c/oresactorturn.h` and
`src/main/c/oresactorturn.c` establishes a VM-private admission and
incarnation boundary, independently of Java runtime class identity. The native
kernel supplies opaque VM-owned actor handles, one-shot cleanup tickets, and
carrier-bound leases, and offers atomic operations for register, claim, STOP,
cleanup claim, cancellation, force revoke, release, and retirement.

This is deliberately **not a public Oreslang FFI**. The C pointers MUST stay
inside the trusted VM. A future compiler intrinsic returns a sealed typed Ores
capability instead of an address or an integer supplied by guest code. The
kernel validates membership before dereferencing a supplied handle, fences
old actor incarnations and foreign registries, binds physical leases to the
claiming native carrier, and prevents the same cleanup ticket from being used
twice. A STOP decision and a new user-turn claim serialize under one lock, so
neither can race an unguarded check. Existing leases must physically exit
before final retirement.

The prototype intentionally uses one VM-global mutex and a lifetime object
budget, which is **correctness scaffolding, not the intended scalable
scheduler**. Allocated handles are not reused until the VM is destroyed to
prevent pointer ABA; therefore admission can fail when the bounded lifetime
budget is exhausted even if many old turns have finished. The host must quiesce
and fence every possible caller before destroying the native VM.

### Extended native admission and teardown stress audit

The native admission regression suite additionally races **200 STOP/user-claim**
interleavings and **200 force-revoke/cleanup-claim** interleavings, with
carrier-owned release, plus forged/cross-VM stack-pointer guesses and malformed
input checks. The number of interleavings is a stress sample, not a formal
exhaustiveness proof; the linked VM-wide mutex is the native linearization
point.

The VM destructor now fails closed until **every actor is STOPPED,
physically quiescent, free of outstanding cleanup tickets, and RETIRED**.
A no-active-lease check alone would have permitted dropping an unretired
actor and its cleanup obligations. The host must independently fence all
threads that can call into this VM before teardown. Opaque pointer addresses
are never reused *within the lifetime of one VM*, but after destroying a VM
the allocator can recycle addresses; old host pointers must never be used
against a new VM. A production handle should additionally carry VM-instance
identity and a generation-scoped secret/nonce independent of allocation
address.

Native compiler-and-test evidence is required on the exact Git tree; successful
registration of a GitHub Actions workflow without executed job steps is not
validation. Cross-org CI mirrors require private/private pinned repository IDs
and exact tree equality before any conformance claim.

**Critical limitations:** Native `force_revoke` fences *future* claims; it
does not asynchronously terminate an already-running native carrier. Cleanup
tickets may only be minted by the trusted scheduler for a real registered
SourceTask; the prototype does not independently inspect SourceTask metadata.
Physical turn admission and actor retirement still require the rest of the
runtime's capability, child, cleanup and output barriers. C pointers are not
safe to export into user code, IPC, Java interop or ordinary Ores values.
This kernel is not linked into the current Java/Truffle actor dispatch and is
not an LLVM guest actor implementation yet.

`stdlib/actorruntime.ores` now separately owns the deterministic
`next_phase` transition policy (REGISTERED, READY, STOPPING, FINALIZED).
It rejects skipping STOP, rejects stale READY after finalization, and requires
quiescence for FINALIZE. Returning a phase integer is an advisory policy
result, **never** permission to commit an actor transition. The VM must
atomically bind a generation-safe authenticated transition lease to that
policy result before changing its private actor state.

Native correctness smoke tests run at `-O0` and `-O2` through
`sh scripts/test-native-actor-turn.sh`; optional ASan/UBSan execution
uses `ORES_NATIVE_SANITIZE=1`. These cover forged and foreign handles,
generation fencing across restart, wrong-carrier release, concurrent one-shot
ticket claims, STOP and force revocation, resource exhaustion, and delayed
physical retirement. The Oreslang guest fixture independently tests source
lifecycle transitions. Both independent suites are necessary; neither by
itself establishes that the live actor scheduler is using the new kernel.

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
