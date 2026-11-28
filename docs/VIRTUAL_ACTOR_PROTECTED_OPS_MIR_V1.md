# AttoLang checked operations MIR v1 — compiler-owned candidate

**State:** draft, source-authored, NOT_EXECUTED. This branch is stacked on protected MIR #2834, which is stacked on the Cart guest transition and generation-fence drafts. No runtime source-level virtual actor has been enabled.

## Added narrow compiler lowering

`VirtualActorProtectedOpsMirV1.lower` first admits the entire parsed Cart-shaped declaration through `VirtualActorProtectedMirV1.analyze`. It then produces private-constructor immutable program/method plans with one fixed, scalar typed instruction sequence:

1. BEGIN_EXCLUSIVE_WRITE_LOAN
2. LOAD_STATE_I64
3. LOAD_ARGUMENT_I64
4. ADD_I64_TRAP_ON_OVERFLOW
5. STORE_STATE_I64
6. END_EXCLUSIVE_WRITE_LOAN
7. RETURN_VOID

This ordering deliberately requires a checked overflow trap before the state mutation. SSA-like registers are scalar values, not pointers or borrow capabilities; no guest callback, arbitrary instruction, alias escape or suspension is representable in the accepted grammar. Method *local* ordinals are sorted by name so declaration order does not change the local plan. They are **not** stable ABI or hot-upgrade dispatch IDs.

`TypeChecker` now asks for this candidate operations MIR before reaching the unchanged `VIRTUAL_ACTOR_MIR_UNSUPPORTED` execution gate. It remains impossible for `define virtual actor` to execute or construct a guest-visible `VirtualActorRef[Cart]` from this patch.

## Acceptance tests and evidence

`VirtualActorProtectedOpsMirV1Test` adds five source-authored JUnit methods asserting a closed exclusive loan, checked-add before store, immutable plans, deterministic local method order, rejection of negative body mutations, exact typed method selection, private plan constructors and continued production rejection. Existing MIR, Cart compiled guest-transition, generation, terminal CONTROL and directory tests stay included in the focused workflow.

A private source-org checkout-free smoke workflow separately checks whether GitHub assigns a runner and executes a step. A zero-step failure is **NOT_EXECUTED** and cannot establish Java test behavior. The reported focused #2834 run had `steps=[]`; issue #963 remains an administrator billing/policy/runner admission blocker. No alternative-organization mirroring of private code is authorized by this patch.

## Still required before executable release

- A verified Java/GraalVM and AOT backend for this typed instruction sequence, actual exclusive borrow lifetime and actor-owned heap/stack, plus checked i64 overflow traps.
- Canonical module-qualified nominal identity, versioned stable method-slot ABI and compiler-generated guest `VirtualActorRef[T]` resolve/invoke; the local ordinal here cannot substitute for that.
- First-invocation activation and generation-fenced asynchronous supervisor terminal CONTROL, physical quiescence, restore-before-publication and provider-issued durable CAS epochs.
- Nonzero exact-head CI/JUnit/Surefire evidence, overflow-to-CONTROL positive and negative guest execution, backend parity, independent review and dependency-ordered merges.

**Do not remove the fail-closed compiler gate until all applicable runtime, AOT and security guarantees have executable proof.**

## Audit hardening: overflow is not a normal return

The candidate operation stream has an explicit, immutable, compiler-owned
`OverflowExit` attached to the trapping addition. It obligates a future
backend to discard the pending write, unwind the exclusive loan, fence the
failed physical generation, emit sanitized terminal CONTROL, and await the
supervisor's asynchronous acknowledgement before any replacement binds.
These are mandatory semantics **not implemented** by this static patch.
`verifyCandidate` rejects skipped/swapped/additional operations and missing
exceptional edges, but cannot authorize execution.

The first backend must implement a true exceptional control-flow edge (not
fallthrough to STORE), trap cleanup and physical quiescence; prove old actor
state never commits after overflow, no stale handle survives and generation
N+1 is fenced until CONTROL ack. A compiler plan alone is not that proof.

Two additional JUnit methods test deletions of each instruction, STORE/add
reordering, injected duplicate STORE, unchecked replacement, null overflow
edge and private exceptional-plan construction. **Source-authored only** until
the source-org runner executes these tests with real reports.
