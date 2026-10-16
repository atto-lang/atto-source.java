# Java/GraalVM — shared actors own private arenas

Status: runtime compatibility implementation in this draft (2026-10-16). **Not** a claim that current `main` or this draft lowers all Oreslang source-language allocations to native arenas.

## Target topology

- MAIN/trusted SHARED actors and trusted PRIVATE actors execute in the existing trusted host/AOT domain. Every actor *incarnation*, regardless of kind, gets a private persistent arena plus scratch per admitted turn. SHARED actor membership grants no ambient access to another actor's persistent arena.
- SHARED actors access genuinely shared state only through distinct **shared-domain-owned** native regions with validated rights and read/write critical leases, plus immutable `Shared<T>`/`SyncCell` semantics as appropriate. The domain and its region survive parent shutdown until all descendants, supervisor owners and borrows are quiescent.
- UNTRUSTED actors remain in **one pooled untrusted Graal isolate** (not one isolate per actor). They each have a logical private actor region, with aggregate isolate cap and an explicit cross-domain serialization boundary. Never leak a native segment/pointer across isolates.

## Audit of current implementation

- `ActorRuntime.ActorInstance` on current main creates `ActorMemorySlice` only when `isPrivateKind(kind)`; SHARED actor allocation is currently charged to broader shared counters/managed runtime. Therefore **SHARED actors do not yet have per-actor FFM arenas by default on main**.
- `ActorMemorySlice.PrivateMemoryBlock` uses `ByteBuffer.allocateDirect` and scoped alias checks. ByteBuffer reachability does not prove deterministic native deallocation or GC absence.
- Java actor-owned persistent/scratch FFM i64 prototype was developed in PR #580 (merged into branch `feat/actor-native-record-mutable-i64-20261010`, **not necessarily in main**). It does not yet cover general class/struct layouts or every `await`/cooperate/cancellation unwind.
- Main Maven compiler config uses `maven.compiler.release=21`. Stable `java.lang.foreign.Arena` was finalized in JDK 22; use the GraalVM/JDK runtime profile that actually supports the stable API, or separate the FFM build module rather than breaking JDK 21 compilation.

## Draft implementation slice (not merged)

- `ActorCell` now creates a per-actor `ActorMemorySlice` for SHARED as well as PRIVATE/UNTRUSTED, rather than returning null for SHARED.
- `ActorContext.localMemory()` gives each actor its own actor-local allocation authority. The legacy `privateMemory()` continues to be visible only to PRIVATE/UNTRUSTED, so it does not become a shared-memory shortcut.
- `ActorMemorySlice.allocateActorLocalBytes()` is an owner-validated direct-byte-buffer compatibility path with per-actor quotas, shared aggregate charging for SHARED, private aggregate charging for PRIVATE/UNTRUSTED, and stop-time invalidation. It is **not** a deterministic FFM arena: direct-buffer native backing still depends on JVM collection for physical release.
- Shared mailbox/output admission includes actor-local used bytes in its per-actor policy budget. Actor-local reservation now also acquires the owning SHARED actor's lifecycle monitor **before** its slice monitor, matching mailbox/output lock order, to close a concurrent admission oversubscription race.
- `ActorSharedLocalMemoryTest` exercises owned direct bytes, foreign-carrier and sibling-SHARED access denial, accounting, retirement and quota failure. CI execution is pending.

## Required integration

1. Reconcile the existing FFM arena implementation from the stacked branch onto current main without overwriting newer actor scheduling, resource-accounting, two-isolate, capability and ownership behavior. Generalize `ActorMemorySlice`/local arena attachment to every shared/private/untrusted actor incarnation while keeping per-kind accounting and independent aggregate caps.
2. Allocate persistent typed object graphs via owner-checked native layouts (with exact drop glue); compiler-emitted actor fields/collections need typed arena lowering. JVM wrapper/control allocations remain GC-managed; disclose and profile them.
3. Begin/end scratch scope at **every** real mailbox/continuation turn. On suspension, cancellation, exception, hot reload and actor end, unwind scratch and reject leaked loans. Continuation/persistent lifetimes need separate proofs rather than unbounded scratch extension.
4. Separate shared-domain arenas from actor-private arenas. `rt share` cannot silently move raw Java/FFM storage to another actor. Require frozen data or explicit rights/leases, bounded synchronization, no untracked pointer sharing. Only authorized domain members can dereference.
5. Before native `Arena.close`, stop new admissions, drain active turns, producers, async callbacks, FFI, outstanding loans and domain references; close only after proven quiescence, exactly once. Domain retention is independent of a shared actor supervisor's own lifetime.
6. Execute Java tests and native-image AOT/pooled-untrusted-isolate tests, then source-level conformance vectors from `oreslang-common-source/contracts/shared-actor-arenas.v1.json`. No production parity claim before these gates pass.

### Negative regression suite

Cross-actor handle replay, ActorRef-as-memory-authority, mutable shared region without lease, wrong isolate, stale scratch after `await`, stale incarnation/ABA, leaking persistent borrow on stop, attempted close under active FFM access, quota under/overflow and zeroization on recovery must fail closed. Do not confuse `Arena.ofShared()` thread safety with authorization to mutate unsynchronized data.

Companion cross-backend contract: `oreslang-common-source` PR #49. Related Java issue #289 (source lowering), #288 (benchmark hybrid local/shared), #304 (intrinsics), #575 (single untrusted isolate), #633 (continuations).
