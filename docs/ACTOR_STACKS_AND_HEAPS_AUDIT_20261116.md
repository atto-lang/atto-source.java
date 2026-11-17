# Actor stacks and heaps: audit and stack-model decision (2026-11-16)

Status: **DRAFT / proposal.** Audit by source reading only: nothing was built or
executed. Every runtime claim below is **NOT EXECUTED** unless a test is named
with a receipt.

Audited heads (each repo's `origin/main` on 2026-11-16):

| Repo | Commit |
|---|---|
| oreslang-source.java (lead) | `5123fa2020` |
| oreslang-source.llvm | `71f97c51b` |
| oreslang-source.wasm | `adf86b6` |
| oreslang-source.rs | `92b0863` |
| oreslang-common-source | `9d82e97` |

## 0. Summary

1. **Decision (2026-11-16): parallel mode is allowed.** An actor may opt in to
   up to N simultaneous physical handler turns. Today
   `oreslang-common-source/contracts/v1/CALLBACK_DISPATCH_AND_PREEMPTION_V1.md:10`
   still says "One actor may not execute on two carriers simultaneously", and
   `docs/formal/ACTOR_EXCLUSIVE_CARRIER_PROOF.md` proves exactly that. Both
   need amending (§1.2). Parallel mode is **not new syntax**. It unlocks the
   existing `requestConcurrency` / `messageConcurrency` actor settings
   (`docs/proposals/actor-concurrency-reentry.md`), which the type checker
   currently rejects above 1 (`TypeChecker.java:1700`). Mutual exclusion
   moves from "one carrier per actor" to "one `ActorStateLoan` per mutable
   state access". The design is in §1.1.
2. **Recommended stack model: carrier stacks, stackless activation frames and a
   per-turn stack budget** (option C, §4). Do not give each actor its own native
   stack, and do not give each `receive` call its own native stack either:
   - Each carrier thread owns one native stack. An actor *borrows* it for one
     turn and must leave it empty when the turn ends. No guest frame survives
     on a native stack across a turn boundary.
   - Each logical activation (suspended receive, `task`, continuation) owns a
     **statically sized, compiler-lowered frame** in the actor's arena. This is
     the "stack per execution context". It is a frame chain in the actor heap,
     not an mmap'd stack.
   - Stack overflow is caught **before** it happens, by compiler-injected budget
     checks that sit next to the existing `rt cooperate` safepoints. The check
     raises an actor-fatal `STACK_EXHAUSTED` fault, which goes to the
     supervisor. Guard pages and signal handlers stay as a last line of defence
     only.
3. **Why this is enough for isolation:** a native stack holds frames from
   exactly one actor turn at a time, and the runtime already forbids nested
   actor turns on one carrier (`ActorRuntime.java:10007`). This stays true in
   parallel mode, because the N turns of a parallel actor run on N different
   carriers, each on its own stack. So overflowing a carrier stack can only
   hurt the turn that overflowed. The remaining risk is
   that an overflow *inside runtime code* leaves shared structures half
   updated, such as another actor's mailbox. A per-turn budget with reserved
   runtime headroom closes that gap. Separate per-actor stacks would not.
4. Per-actor heaps are **real on the arena substrate but mostly not on the
   guest allocation path** in any backend. Hardening items: §5.

## 1. Premise check: how much physical concurrency per actor?

| Source | Rule |
|---|---|
| common `CALLBACK_DISPATCH_AND_PREEMPTION_V1.md:10-11,20` | One physical lease per actor. Logical reentrancy only during a *genuinely pending* await, per the actor's declared policy. Serial actors keep logical exclusion across awaits. |
| Java `docs/ACTOR_CONTINUATION_LEDGER.md` and `ActorRuntime.java:2949` | One physical lease. At most **one** suspended source receive per actor (a second one throws). Logical reentrancy policy is **not implemented**. |
| LLVM `NativeActorLogicalFramesV1.h:22` | "A regular actor can have multiple simultaneous physical receiver turns" (`max_turns <= 64`). **Contradicts common.** Has no callers outside its own test. |
| Whitepaper 04 §4 | "An actor may own multiple tasks, subject to its turn and reentrancy contract." The reentrancy contract itself is unspecified. |
| Java RFC `docs/proposals/actor-concurrency-reentry.md` | `serial` = `requestConcurrency = 1, messageConcurrency = 1`. Values above 1 parse but are rejected until per-request execution and state loans exist. `ActorStateLoans` is built but not attached to anything. |

**Decision: allow parallel mode** as an explicit opt-in. The default and
`serial` actors keep exactly one physical lease. The stack model in §4 is
unchanged by this decision. That is a key reason to choose it.

### 1.1 Parallel mode design

**Declaration.** An actor enters parallel mode by declaring
`messageConcurrency = N > 1`, and for HTTP/TCP/WS actors also
`requestConcurrency`. There is no new keyword. `serial` stays shorthand for
1/1. Parallel mode is never the default, including for SHARED.

**Physical concurrency invariant**, replacing `count <= 1`:

    forall actor a: count(executing carriers for a) <= leases(a)
    leases(a) = 1                                    if serial or default
              = requestConcurrency * messageConcurrency   otherwise

`leases(a)` is capped at the parallelism of the actor kind's dispatcher pool,
so one actor can never occupy a whole pool. The global
`ActorPhysicalTurnGate` counts each physical turn separately.

**Which kinds may use it:**
- PRIVATE and SHARED: yes.
- UNTRUSTED: **rejected in v1.** Implicit cooperation is already disabled
  for untrusted actors, quota continuity and revocation are unproven, and N
  parallel untrusted turns would multiply the attack surface.
- Wasm browser backend: **rejected in v1.** One Worker per actor has no
  shared-memory path without SharedArrayBuffer and an audited
  memory-sharing design.

**State access rule, enforced statically.** In a parallel actor, every read or
write of actor-state fields happens inside an `ActorStateLoan` scope:
- v1 provides exclusive loans only, which is what `ActorStateLoans` implements
  today. Shared read loans come later.
- A loan follows the *logical handler* (the activation), not the OS thread.
  The *logical lease* may be held across `await`, as the RFC says. Holding it
  blocks conflicting handlers. Live mutable references derived from the loan
  may **not** cross the suspension (common rule 6). After resuming, the
  handler must borrow again under the same lease. A critical section is never
  silently unlocked on an implicit yield.
- The type checker admits N > 1 only when there are no field accesses outside a
  loan, no mutable aliases leaving a loan scope, and no handler-local
  reference stored into actor state without an explicit `rt take` or
  `rt copy`.

**Memory layout of a parallel actor**, which is also the heap-hardening target:

| Region | Owner | Lifetime | Concurrency |
|---|---|---|---|
| Actor-state region | actor | actor generation | accessed only under `ActorStateLoan` |
| Handler-local region | one activation | freed in one step when the handler completes, fails or is cancelled | single writer (its own activation) |
| Activation frame chain (§4) | one activation | per `suspends` frame | single writer |
| Allocation buffers | one physical turn | carved from the actor arena | lock-free bump allocation. The arena lock is taken only to refill a buffer |

Request-local and message-local state in the RFC are the same thing as the
handler-local region. Because each region is freed in one step, per-request
allocation needs no GC.

**Ordering.** Admission (start) order stays FIFO. Completion order is
unspecified when N > 1. For transport actors, order within one connection is
kept by the per-connection `messageConcurrency`. Documentation must say that
per-sender processing order is not guaranteed in parallel mode.

**Control, cleanup and upgrade paths** (supervisor CONTROL, STOP, graceful
teardown, hot upgrade) are **exclusive**: they close admission, then wait for
all N physical turns to quiesce. Writers get priority, so new handlers cannot
starve them.

**Failure semantics.** Stack exhaustion, a trap or a deadline kill in any one
of the N handlers is **actor-fatal** in v1:
1. Admission closes.
2. Sibling handlers are cancelled at their next safepoint.
3. Finalization waits for all N turns to physically quiesce.
4. The supervisor restarts the actor.

This is the safe default, because a handler holding an exclusive loan may
have left actor state half-written. A possible refinement is to treat a
failure as **activation-fatal** (fail only that message) when the failing
handler held no write loan. That needs its own proof, so it is an open
question (§7).

### 1.2 Contract and proof amendments required

1. **common `CALLBACK_DISPATCH_AND_PREEMPTION_V1.md` rule 4**, proposed text:
   > Actor guest work always runs under one of the actor's physical execution
   > leases. A serial or default actor has exactly one lease and may not
   > execute on two carriers simultaneously. An actor that declares
   > concurrency N > 1 (parallel mode) has at most N leases. Every access to
   > its actor-state fields is made under an `ActorStateLoan`. Handler-local
   > state lives in a per-activation region that cannot alias actor state or
   > another activation. CONTROL, cleanup and finalization take exclusive
   > authority over all leases.

   Update the terminology section to match: "logical actor reentrancy" for
   serial and default actors, "parallel mode" for N > 1.
2. **`docs/formal/ACTOR_EXCLUSIVE_CARRIER_PROOF.md` and
   `ReentrantActorExclusiveCarrier.tla`.** Generalise `Claim` to
   `leases_held(a) < leases(a)`, and add these invariants:
   - (i) loan exclusivity: at most one exclusive loan per actor-state cell at
     any instant;
   - (ii) no state access without a loan;
   - (iii) CONTROL runs only when `leases_held = 0`.

   Keep the existing mutation test, and add new ones that disable the loan
   check or the CONTROL drain and must find a counterexample.
3. **LLVM resilience contract item 22**, "a failed turn leaves no state
   change". For parallel actors, the guarantee is restart from the last
   committed state, because v1 failures are actor-fatal (see above).

Naming: the code's actor kinds are `PRIVATE`, `SHARED` and `UNTRUSTED`
(`ActorRuntime.dispatcherFor`). "Isolated actor" in conversation means
`PRIVATE`.

## 2. Thread and execution topology (as built in Java)

- Control plane: `OresVM` platform threads and the main loop, plus the
  `OresScheduler` pool for source tasks (`OresScheduler.java:131,313`).
- One actor dispatcher per kind: `ores-{private,shared,untrusted}-actor-dispatcher`
  (`ActorRuntime.java:861-875`). Each is either a `NativeCarrierExecutor`
  (pthreads, `liboresthread`) or a `ThreadPoolExecutor` fallback, sharded into
  affinity lanes by `ActorAffinityDispatcher`.
- Native carrier stack defaults to **2 MiB**
  (`NativeCarrierExecutor.java:121`, `ores.actor.carrier-stack-bytes`,
  256 KiB–64 MiB). All actors scheduled on a carrier share that stack, one
  turn at a time.
- Suspension is **stackless**. A receive that may suspend runs as a heap-owned
  `SourceTask` in CPS form (`OresEvalRootNode.java:743-760`). Continuations
  are JVM lambdas (`SourceValueCont`) driven by a trampoline. Synchronous guest
  calls are still direct JVM recursion (`OresEvalRootNode.java:5646`), so deep
  guest recursion consumes the carrier stack.

LLVM follows the same shape. `NativeExecutionContextStore.h` holds "values and
handles only; no native stack pointers", and continuations are separate store
records. The codegen has no coroutine lowering yet (no `llvm.coro.*`).

## 3. Stack overflow: current behaviour per backend

| Backend | What happens on guest stack exhaustion today | Meets "fails only that actor"? (LLVM `ACTOR_RUNTIME_RESILIENCE_V1.md:258`) |
|---|---|---|
| Java (HotSpot/Graal JIT) | There is no guest call-depth limit. A JVM `StackOverflowError` is thrown at an arbitrary point, which may be inside runtime code holding another actor's mailbox or shared state. `runBatchEntered` calls `fail(actor)`, then **rethrows** the error (`ActorRuntime.java:10214`, also `:9511`, `:9953`). On native carriers it reaches `dispatchUncaught` and the carrier survives (`NativeCarrierExecutor.java:183`). On the JVM fallback pool the worker thread dies and is replaced. `finally` blocks deep in the stack run near the limit and can fail a second time, skipping cleanup. | **Partially.** The actor is failed and the process survives. Shared-state consistency is **not** guaranteed when the overflow lands in runtime code. |
| Java (Native Image) | Same code path. SVM stack-overflow checking applies. | Same, **NOT EXECUTED**. |
| LLVM | Carriers are pthreads with default or realtime attributes (`NativeActorScheduler.c:859,883`). There are no prologue checks, no `sigaltstack` and no SIGSEGV handler. | **No.** A guard-page hit kills the whole process. |
| Wasm (wasmtime spike) | `max_wasm_stack(1 MiB)` traps `StackOverflow` per instance (`spikes/tenant-vm-wasmtime/src/main.rs:56,123`). | **Yes, in the spike only.** `aot-host/profile.rs` sets `consume_fuel` but not `max_wasm_stack`. |
| Wasm (browser playground) | One Web Worker and one instance per actor. A JS `RangeError` is confined to that worker. | Yes, for that environment (whitepaper 04 §8). |
| Rust (`oreslang-source.rs`) | Scalar compiler only. It has no actor runtime. | Not applicable yet. |

## 4. Decision: the stack model

### Options

| | A. One native stack per actor | B. One native stack per activation (goroutine-style) | **C. Carrier stacks + stackless frames + budget** |
|---|---|---|---|
| Overflow isolation | Good, but needs guard pages and a signal handler per stack | Same as A | Good: prologue check plus runtime headroom, and the stack is empty between turns |
| Works with logical reentrancy | **No.** N in-flight receives need N stacks, which collapses into B | Yes | Yes. Each activation has its own frame chain |
| Works with multi-physical turns | No | Yes | Yes |
| Memory at 1M actors | ~64 GiB reserved at 64 KiB each, and at least 1 page committed per actor | Worse (per activation) | 0 stack per idle actor. Each suspended activation costs only its statically sized frame (bytes to a few KiB) |
| Zero-GC / full AOT | Needs growable stacks (copying needs precise stack maps, segmented stacks are slow) or fixed worst-case reservations | Same | Frame sizes are known at compile time and come from the actor arena. No GC, no stack copying |
| Fits the existing language | Lets you suspend anywhere, but ignores the `suspends` effect | Same | Matches `suspends` exactly. The effect already colours which calls need lowered frames |
| Fits existing code | Java can't do it without Loom virtual threads (GC'd stack chunks) | Java via Loom only | Java is already stackless (CPS SourceTask). LLVM's execution-context and continuation stores are already pointer-free |
| Context-switch cost | Stack switch on every turn | Same | None. The turn runs directly on the carrier |

### Recommendation: C

**Mapping to the concepts in the request:**

- **Execution context ↔ activation.** Each admitted receive, `task` or
  continuation gets one `ExecutionContext` record (already present in Java and
  LLVM) that points at its **activation frame chain**. The frame chain is that
  activation's "stack". It is:
  - allocated from the owning actor's arena (a dedicated frame region), sized at
    compile time per `suspends` function: live typed locals, resume point,
    cleanup state, loan state and actor generation, as
    `SUSPENDS_AWAIT_EXECUTION_CONTEXT_V1.md` already requires;
  - charged to the actor's quota and to a per-actor `max_live_activations`;
  - scrubbed and released exactly once, on completion, cancellation or STOP,
    behind the existing generation fences.

  Recursion through `suspends` functions allocates one frame per level, bounded
  by a per-activation frame budget, so it reports `STACK_EXHAUSTED` the same way.
- **Native stacks belong to carriers, not actors.** There is one per thread in
  each pool (control, ores-scheduler, private, shared, untrusted). Each pool
  sets its own carrier stack size. `UNTRUSTED` should get a smaller per-turn
  budget than `PRIVATE`/`SHARED`.
- **Isolation invariant:** when a turn ends, the carrier stack depth is back to
  the carrier loop and holds no guest frames. Nested turns are already
  rejected. So a stack fault can only ever affect the turn that caused it.

### Overflow containment design (layered)

1. **Static bound (AOT, preferred).** For turn entry points whose call graph
   has no cycles, compute the worst-case stack use at compile time. LLVM
   already computes `max_call_depth` in `RealtimeAnalysis.hpp:625`; extend it
   to bytes using per-function frame sizes. Admit the handler only if
   `bound <= kind budget`. Such a handler needs no runtime checks at all.
2. **Dynamic budget check, compiler-injected** next to `rt cooperate`
   safepoints, but only on recursive call edges (strongly connected components)
   and in functions with large frames:
   - At turn entry, the runtime stores `turn_stack_limit = sp_entry - budget(kind)`
     in carrier TLS.
   - LLVM: in the prologue, `if (sp - frame_bytes < turn_stack_limit) ores_trap(STACK_EXHAUSTED)`.
     This is portable, unlike `split-stack`.
   - Java/Truffle: keep a guest call-depth counter in the call path around
     `invoke(...)`, with a per-kind depth budget calibrated so that
     `depth_budget × max_interpreted_frame ≤ carrier_stack − runtime_headroom`.
     Throw `ActorStackExhausted`, a runtime control-flow failure that guest
     `try` cannot catch, the same way cancellation works.
   - **Runtime headroom**, for example 256 KiB, is never available to guest
     code. Runtime critical sections such as mailbox enqueue, ledger updates and
     arena metadata therefore always have stack, and a real overflow never lands
     inside them.
3. **Fault semantics.** `STACK_EXHAUSTED` is actor-fatal, the same as a trap
   or a turn-deadline kill (`ACTOR_RUNTIME_RESILIENCE_V1.md:59`). Because
   item 22 already requires that a failed turn commits no state and sends no
   mail, it is semantically safe to abandon the turn. Arena-backed state makes
   it physically safe too: turn allocations are discarded with the activation.
   The supervisor receives
   `{actor, generation, kind, depth_or_bytes, budget, entry_point}` and applies
   restart policy and budget as usual.
4. **Last line of defence (native).** Carrier stacks get mmap'd guard pages and
   a `sigaltstack` SIGSEGV handler:
   - v1: if the fault address is in a carrier guard page, write a crash record
     naming the actor and exit. This is honest, because it means the prologue
     checks were bypassed, for example by FFI.
   - v2, after review: mark the actor failed, retire the carrier (never reuse a
     torn carrier), start a replacement, and keep the process running. A fault
     outside a guard page stays VM-fatal, per the contract.
5. **Java residual `StackOverflowError`.** Treat it as actor-fatal at the turn
   boundary: do not rethrow it to the carrier, unlike OOM, `InternalError` and
   `LinkageError`. Also increment a `runtime_suspect_overflows` counter,
   because reaching this path means the budget was mis-calibrated.

### Interaction with `rt cooperate` and preemption

The budget check and the cooperation check share an injection site and a trap
path. A cooperate handoff can only release the carrier at a lowered
continuation point (common contract rule 6). That is consistent with C:
synchronous frames are never parked on a native stack. A handler that is
non-`suspends` and CPU-bound still runs to the turn deadline. The watchdog
then kills it (actor-fatal), not the process.

## 5. Heap audit findings and hardening items

### Java (lead)

- **J-H1.** Ordinary guest objects live in the one shared JVM heap. Per-actor
  heap isolation is enforced logically, through the ownership checker plus
  `freeze` at the mailbox boundary. It is not physical. `ActorHeapAdmission`
  correctly refuses zero-GC claims. *Harden:* per-actor allocation accounting
  for guest objects, so that exceeding a quota is the actor-fatal
  `ACTOR_HEAP_EXHAUSTED` instead of a process-wide `OutOfMemoryError`.
  (Whether guest-object bytes are charged today is not verified. Check that
  first.)
- **J-H2.** `ActorLinearArena` is wired as the opt-in `PrivateLinearArena`
  facade (`ActorRuntime.java:1519-1620`): owner-checked, close severs it,
  quota charged. `ActorNativeArena` (JNI) is reachable only through
  `ActorLinearMemory`, which `ActorRuntime` never uses. `ActorOwnedGraphArena`
  and `GenerationalGraphArena` have **no runtime callers**. *Harden:* wire
  them into actor-state lowering, or label them experimental so audits stop
  counting them.
- **J-H3.** Continuations are per-await JVM lambdas, which are GC'd.
  `ATTO_CODE_IMAGE_CONTINUATION_LEASE_20261114.md` also notes that a
  `CodeLease` object is allocated per continuation. *Harden, towards
  minimal GC:* use preallocated per-actor activation-frame slots, so the
  continuation ledger indexes slots instead of objects.
- **J-H4.** There is a hard limit of one suspended source receive per actor
  (`ActorRuntime.java:2949`), and `beginTurn()` rejects concurrent
  `activeTurns`. Parallel mode needs:
  - `activeTurns <= leases(a)`;
  - per-activation suspension slots in place of the single
    `sourceReceiveSuspended` flag. These slots are where the activation-frame
    region from §4 lives.
- **J-H6 (parallel mode).** `ActorLinearArena` / `PrivateLinearArena` assume
  a single current owner and turn (`slice.requireCurrentOwner()`). They need
  per-turn allocation buffers and per-activation handler-local regions.
  `ActorStateLoans` must be attached to actor-field access in both the
  interpreter and AOT lowering, with the type-checker rule from §1.1. Only
  then should `TypeChecker.java:1700` admit N > 1.
- **J-H5.** Stack items from §4: depth budget, runtime headroom, and handling
  `StackOverflowError` as actor-fatal.

### LLVM

- **L-H1.** Arena and region misuse calls `abort()`
  (`ActorArena.c:35`, `ActorRegionV1.c:17`), as do 53 sites in
  `NativeActorScheduler.c`. One actor's forged or stale handle therefore
  takes down the whole process. *Harden:* when a check *detected and
  prevented* the access, raise an actor-fatal trap. Keep `abort()` for broken
  runtime invariants only.
- **L-H2.** Arena lowering exists only behind
  `--actor-region-v1-guest` / v2, for a narrow subset. Default codegen uses
  stack-local class lowering, so per-actor heaps are not on the general
  allocation path.
- **L-H3.** `NativeActorLogicalFramesV1` is unwired. Its "multiple
  simultaneous physical turns" now matches parallel mode, but only if
  `max_turns` is taken from the declared `leases(a)`, with `serial` and
  default meaning 1. *Harden:* reuse it as the per-activation frame store
  (one frame chain per *activation*, so suspended activations do not hold a
  turn slot), and wire it to the scheduler.
- **L-H6 (parallel mode).** `NativeActorScheduler.c` uses a boolean
  `ActorLane.active`, and `ActorArena.c` `require_turn` assumes one active
  turn per arena (`a->active`, `carrier_arena == a`). Both need a counter
  bounded by `leases(a)`, plus per-turn allocation buffers. A CONTROL turn
  must drain all leases first.
- **L-H4.** No continuation lowering. Prerequisite for C: lower `suspends`
  functions to state machines whose frames live in the actor arena. This is
  equivalent to C++20/Rust coroutines, without heap allocation outside the
  arena.
- **L-H5.** Stack items from §4: prologue checks, explicit carrier stack size,
  guard pages, `sigaltstack`.

### Wasm

- **W-H1.** Set `max_wasm_stack` in `aot-host/profile.rs`, mapping each actor
  kind to a budget. Already proven in the spike.
- **W-H2.** One instance (linear memory) per actor already gives a physical
  per-actor heap and stack in the browser. For in-process hosts running many
  actors per instance, the activation-frame region from §4 applies.

### Rust

- **R-H1.** No actor runtime. When one is added, adopt C from the start.

## 6. Acceptance tests (to write; all NOT EXECUTED)

Run the same `.atto` fixtures on each backend:

1. `stack-exhaust-recursive.atto`: actor A recurses without bound and actor B
   ping-pongs concurrently on the same pool. Expected: A gets
   `STACK_EXHAUSTED`, its supervisor restarts it, B is unaffected, and the
   process exits 0.
2. `stack-exhaust-during-send.atto`: deep recursion that calls `send` near the
   budget. Expected: the recipient's mailbox stays consistent and the message
   is either never published or published exactly once.
3. `stack-exhaust-suspends-recursion.atto`: recursion through `suspends`
   functions. Expected: frame-budget exhaustion, with arena bytes returning to
   baseline after restart.
4. `reentrant-activations.atto`: a default actor has N in-flight receives that
   each await. Expected: N activation frames are live, never more than one
   physical turn runs at a time, and the (N+1)th receive waits in admission.
7. `parallel-overlap.atto`: an actor with `messageConcurrency = 4` and
   handler-local work only. Expected: 4 turns are observed overlapping
   physically, never 5, and each runs on a different carrier.
8. `parallel-withdraw-race.atto`: the RFC's withdraw(80) racing withdraw(30)
   against balance = 100 under N = 4, across `await` and preemption. Expected:
   both withdrawals are never admitted.
9. `parallel-stack-exhaust.atto`: one of 4 parallel handlers recurses without
   bound. Expected: the actor is failed, its 3 siblings are cancelled at a
   safepoint, the restart happens only after all 4 turns quiesce, and other
   actors are unaffected.
10. Negative cases for parallel mode:
    - field access outside a loan is a compile error;
    - a handler-local reference stored into state without `rt take`/`rt copy`
      is a compile error;
    - UNTRUSTED with N > 1 is a compile error;
    - Wasm with N > 1 is a compile error;
    - `serial` with N > 1 stays a parse error.
5. `untrusted-budget.atto`: an UNTRUSTED actor gets the smaller budget, a
   PRIVATE sibling runs the same recursion successfully, and the decision is
   recorded in the receipt.
6. Negative cases: guest `try` cannot catch `STACK_EXHAUSTED`. A static-bound
   rejection happens at compile time for an acyclic handler that exceeds its
   budget.

## 7. Decisions needed

1. ~~Single lease or parallel mode~~. **Decided 2026-11-16: parallel mode,
   opt-in through `requestConcurrency` / `messageConcurrency`** (§1.1).
   Next steps: land the common-contract amendment and the formal model update
   (§1.2) before any runtime admits N > 1.
2. Adopt option C as the cross-backend stack model.
2a. Parallel-mode failure scope: actor-fatal (v1 default), or activation-fatal
    when the failing handler held no write loan.
2b. Add shared (read) `ActorStateLoan`s in v1, or defer them.
3. Starting per-kind turn stack budgets and runtime headroom. Proposed:
   PRIVATE and SHARED get 1 MiB of a 2 MiB carrier stack, UNTRUSTED gets
   256 KiB, and runtime headroom is 256 KiB.
4. Whether v2 native guard-page containment (retire the carrier and keep the
   process running) is in scope, or v1 (crash record and exit) is enough
   alongside prologue checks.
