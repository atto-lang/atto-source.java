# Mutex, actor state RW loans and supervisor synchronization audit

Status (2026-10-18): **incremental hardening; multi-carrier actor safety is NOT fully proven**.
This audit covers Java/Graal OresMutex, scheduler and actor-state entry, and the
native LLVM CriticalGate / LogicalMutex / ActorStateScope substrates. Do not
interpret JVM mutex availability or static borrow checking as an end-to-end
data-race-freedom proof.

## Nonnegotiable execution model

* Default actor physical concurrency remains min=1/max=1; an explicitly
  enabled max>1 must be backed by verified multi-carrier admission, synchronized
  mutable-state loans and CONTROL/teardown barriers. Do not silently use pool
  size or request/message concurrency as an actor's physical thread limit.
* A supervisor is also multithreaded infrastructure: CONTROL dispatch,
  membership, child lifecycle, restart strategy and shared-domain publication
  may race. Supervisor synchronization must not depend on a Node-style
  single-event-loop assumption.
* An **actor domain** identifies ownership and permissions. It is *not* an
  exclusive lock owner when several turns can run in that actor. A **physical
  execution segment** identifies one executing dispatch. An **authenticated
  logical continuation** must identify ownership across suspension and carrier
  migration. These three identities MUST NOT be conflated.
* The single persistent actor `state { ... }` aggregate is the only ordinary
  actor mutable state location. Its access is through sealed `handle_state`
  and verified `using state.read` / `using state.write` effects, never
  unrestricted instance fields. `with` is an effect/ownership boundary,
  not an escape from the borrow checker.
* Protect different resources with different tools: OS/host mutexes for short
  runtime metadata transactions; atomic acquire/release for counters and
  publication; scheduler-owned nonparking logical mutexes for guest actor
  contention; bounded RW admission and tracked loans for mutable actor state.

## Concrete Java finding and scoped fix in this branch

Before: `OresMutex.Local<T>` used a plain `boolean held` keyed only to the
stable actor execution domain. A future opt-in multi-carrier actor could make
two carriers observe `false` concurrently, yielding overlapping guards and
an unsynchronized write race. A guard could also be passed to a different
physical turn of the *same* actor without detecting the transfer.

Now: CAS on an opaque segment owner serializes acquisition; guards compare
identity against the segment that acquired them. Recursive acquisition in the
same segment still raises `RecursiveLockException`; contention from a
different physical segment raises `MutexContendedException`. `tryLock`
remains strictly nonparking. Stale cross-segment use and release are rejected,
including when two dispatches reuse the same JVM Thread.

This is a fail-closed **safety** improvement, not a complete scheduler-managed
local-mutex waiter queue. `Local.lockAsync` remains immediate/exceptional
when busy. As before, a source-level borrow or guard cannot legally outlive
a nonvalidated suspension or turn. Runtime enforcement complements, but
does not replace, guest ownership checking.

## Open P0s: block multi-carrier release until closed

1. **Logical owner through suspension.** Java `SharedMutex` currently keys
   `activeDomains` and the wait-for graph on the actor's stable execution
   domain. Thus two independent concurrent turns in *one* actor look like
   recursive acquisition, while the future continuation that actually owns
   a lock may migrate carriers. Replace that key with scheduler-minted,
   incarnation/generation-fenced logical continuation IDs. Preserve actor
   domain as a separate *authorization* check. Never use raw JVM Thread,
   caller-forged integers or value-equal ActorExecutionContext records as
   cross-suspension lock identities.
2. **One-state RW admission.** Compile `handle_state` into an actor-owned,
   bounded, fair read/write admission gate. Multiple read loans may coexist
   only when they expose deeply readonly, immutable or frozen views.
   A write loan is exclusive against *all* reads and writes; nested scopes
   require explicit compatibility, with no unproven read->write upgrade.
   A loan is tied to actor incarnation + logical continuation + loan
   generation + access mode. The compiler rejects leaks into closures,
   `Future`, async tasks, FFI, unsafe aliasing, return values or messages.
3. **Suspension and preemption.** Safe-point preemption may change carriers.
   It must not split a borrowed mutable critical region. Before `await`,
   `select`, blocking I/O or a preemptive yield that releases admission,
   every mutable/readonly state-backed loan must retire or be safely
   continuation-pinned under an explicit verified contract. Re-entry uses a
   new generation-fenced scheduler ticket. A function's `using` row
   advertises effects; it does not grant lock authority.
4. **Cancellation/STOP/panic.** A cancelled future is *not* proof that the
   executing callback stopped. Keep mutex/state leases, message credits and
   native memory alive until physical quiescence, then release exactly once.
   Do not run callbacks/restarts/user destructors while holding CONTROL or
   transport locks. Poisoning/recovery must not publish half-mutated shared
   state after failures.
5. **Supervisor concurrency.** Explicitly document and model lock order
   (runtime registry -> group/child membership -> actor lifecycle -> mailbox
   / continuation -> actor state), or use staged/transactional mutation that
   avoids nested acquisition. External awaits, IPC, blocking I/O, FFI and
   guest callbacks occur outside metadata locks. CONTROL failure messages
   must be authenticated, lossless, ordered per source and never starved
   behind user mailbox admission.
6. **Native source parity.** LLVM's `CriticalGate` and `LogicalMutex` use
   short host locks plus logical tickets; `ActorStateScope.h` explicitly
   assumes one admitted logical lane and is not itself thread-safe. Those
   lower-level objects do not prove LLVM guest-source Mutex/SharedMutex or
   RW `handle_state` safety. Add a native, real RW loan adapter and
   compiler checks before raising actor physical max on LLVM. Bare-metal
   core must remain independent of POSIX or a mandatory OS mutex.
7. **Memory model.** Every guest protected payload mutation must be
   sequenced-before a release and the next owner's acquire. Enforce
   acquire/release barriers across Java, LLVM atomics, shared/native
   regions and cross-process IPC. Actor-local `state` must not be exposed
   as a raw mutable reference to parallel carriers. Do not equate volatile
   alone with a protected compound operation.

## Required adversarial test matrix

| Area | Minimum release gate |
|---|---|
| Local mutex | two same-actor carriers race to lock; exactly one owner; reentry denied; stale guard rejected |
| Shared mutex | 3+ same-actor logical handlers plus cross-actor access; fair bounded async queue; cancellation-vs-handoff; no domain collision |
| RW state | 100 concurrent readers, exclusive writers, no read->write upgrade deadlock, epoch/loan revocation, no writer starvation |
| Scheduler | safepoint/preempt/resume on different carrier, await with live loan compile-negative, cancellation during native callback |
| Supervisor | concurrent child PANIC + STOP + restart + move, CONTROL-before-teardown, reentrant callback and lock-order inversion probes |
| Native | TSan stress separately from ASan/UBSan, stale ticket, generation exhaustion, quiescent reclamation, freestanding build |
| Static proofs | compiler CFG alias/borrow checks, effect-bound enforcement, model checked bounded queue and lifecycle transitions |

Use deterministic gates/latches first, randomized state-machine stress second,
formal TLA+/model checking and sanitizer-backed builds third. CI receipts must
pin exact source tree and test binaries. Do not call the whole system
race-free merely because parser tests or host-only mutex microtests pass.

## Promotion criteria

P0 (this branch): fail-closed CAS owner for Java actor-local mutex, guard
physical-segment enforcement and targeted regression test.

P1: continuation-ID keyed Java SharedMutex + nonparking waiter admission,
source borrow safety and Java `state` RW admission integrated with true
actor max>1; concurrent supervisor lifecycle model.

P2: LLVM source-codegen/ABI parity, high-contention RW benchmarks, TSan and
model checker CI, complete restart/STOP/supervision and preemption matrix.

**Policy:** retain the existing max=1 runtime fence on code paths without
P1/P2 proofs. Enabling max=3 from syntax/metadata alone is unsound.
