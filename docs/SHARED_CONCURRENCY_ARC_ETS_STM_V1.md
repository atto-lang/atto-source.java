# Shared memory, Arc locks, ETS tables and STM — Oreslang v1 contract

**Status (2026-10-18): proposed language/ABI contract and limited host reference.** No new guest parser syntax, AST lowering, actor-accessible ETS API, native backend, or STM runtime is implemented by this document. The only new executable slice on this branch is `OresEtsTable`, an unexposed *trusted-host* public set reference with JUnit tests.

## 1. Compatibility with existing Oreslang policy

* Actor-owned mutable state stays in the sole sealed actor `state { ... }`; a public shared-memory handle MUST NOT grant access to that sealed region.
* Preserve `critical-read` / `critical-write` effect vocabulary (including `critical` as exclusive alias). Signature effects alone do not grant access: a `using` loan and the *same* underlying RW gate are mandatory.
* Never use a JVM carrier thread ID as logical mutex/guard ownership. M:N Task continuation, actor incarnation, trust domain, grant generation and cancellation/teardown fencing all matter.
* No `await`, `select`, scheduler yield, `*_blocking`, cross-domain callback, or unknown native/FFI call while a borrowed mutable or shared guard is live. Admission may suspend **before** the loan begins; the body cannot suspend.
* Lock guards and table storage never escape as raw writable host pointers; transitively immutable snapshots may be copied or independently reference-counted by approved allocator/trust-domain rules.
* No implicit OS mutex, malloc or process service in `core`. Embedded profiles may decline atomics unavailable on the target; unsupported profiles fail closed.

## 2. Rust-style ownership and synchronization

`core` contracts: `Send` (safe owned-value movement between Task/thread execution contexts), `Sync` (safe sharing of immutable references across contexts: `&T: Send` iff `T: Sync`) and separate `Sendable` (actor transport admissibility subject to owner/domain/capability rules); atomic ordering, borrow lifetimes and zero-allocation polling types. Neither `Send` nor `Sync` permits unguarded simultaneous mutable aliases, and actor transport is not automatically permitted by either trait.

`alloc` facilities, with explicit allocator:

- `Arc<T>`, `Weak<T>`: clone and drop strong/weak counts atomically; one allocation/control block; overflow must trap/fail closed. `Arc<T>` does **not** magically make interior mutability safe. Admit cross-Task shares only if `T: Sync`; cross-actor shares additionally require explicit SHARED_MEMORY authorization in the permitted domain.
- `Mutex<T>` and `RwLock<T>`: pinned protected payload; lexical `MutexGuard<T>` and `ReadGuard<T>`/`WriteGuard<T>`; full memory acquire/release ordering; configurable poison/recovery rules; no lock guard crosses `await`.
- `Arc<Mutex<T>>` and `Arc<RwLock<T>>` are supported compositional types. `SharedMutex<T>` can be a compatibility facade, not a separate ownership/memory model. Do not use an actor-owned heap as an Arc control block accessible by another actor.
- `Atomic<T>` for supported scalar widths and `AtomicRef<T>` only for independently owned immutable/persistent values with versioned safe reclamation. Atomic load/store is not a compound atomic transaction.
- `Shared<T>` on a shared class is source-level convenience with the same ownership/refcount semantics, not an implicit global heap or a free-floating mutable alias.

Proposed, intentionally NON-executable guest examples:

```ores
let Arc<Mutex<Counter>> c = Arc.new(Mutex.new(Counter()));
using c.lock() as let guard {
    guard.value += 1;
}

let Arc<RwLock<Config>> config = Arc.new(RwLock.new(Config()));
using config.read() as const r {
    use_config(r);
}
```

For a `define shared class Counter ... state { ... } end` declaration, direct guest field access to `state` is forbidden. A `critical-read` method acquires a real read ticket; `critical-write` acquires an exclusive write ticket. The compiler verifies the effects of calls, aliases, traits, inherited and virtual methods, and FFI.

### Acquisition and fairness

Shared gates use scheduler-owned bounded waiter queues, reader cohorts, a writer barrier and authenticated logical-continuation ownership. Waiting releases the carrier; an ordinary host thread may use a separate explicitly blocking interface. Treat lock poisoning as state-invariant failure, not a memory-safety workaround. Abort and cancellation must release a granted-but-not-resumed ticket exactly once, while a *running* callback retains its pin until physical unwind. Fail closed on unproven recursive locks, lock upgrades and multi-gate order.

## 3. Erlang ETS-style typed tables

Recommended API: `EtsTable<K,V>` with `TableId<K,V>` as an unforgeable generational capability; support `set`, `ordered_set`, `bag`, `duplicate_bag` as distinct table kinds. The initial actual backend implements **set only**. Tables are in-process local storage, not network/durable databases.

Required options and behavior:

- Ownership: table owns a lifetime; default `protected` (owner read/write, authorized others read). `private` owner-only; `public` authorized local readers/writers. Permission is checked against authenticated runtime actor/domain principal at each operation; a plain user string is not an owner identity.
- Heir: explicit same-runtime/same-trust-domain heir can accept ownership on owner STOP; absent an authorized heir, retire the table and revoke IDs. Never transfer by guessing actor name/ID; on restart use generation checks and a deliberate rebind.
- `named_table`: registration through bounded per-domain registry, names unforgeable via lookup capability checks, stale names and IDs fail after retirement.
- `read_concurrency` / `write_concurrency`: advisory tuning only, **never** an authorization change or relaxed atomicity promise. Partitioning/sharding is internal.
- Linearizable single-key operations: `lookup`, `insert`, `insert_new`, `take`, `delete`, `compare_exchange`, integer `update_counter`; `match`/`select` and iteration are explicitly weakly consistent unless a separately validated snapshot is requested.
- `lookup` returns an immutable owned snapshot/value or approved immutable shared allocation, not a raw mutable alias to ETS storage. If values are mutable, copy/freeze before insertion and on read, with byte quota accounting.
- Atomicity of one entry is not multi-key transactional isolation. Use STM for cross-key invariants.
- All operations and memory are bounded by quotas, including table count, entry count, byte footprint, ongoing probes and iteration responses. Defend against adversarial hash collisions and untrusted key comparators.

Proposed, not yet executable:

```ores
let TableId<string,int> scores = ets.new<string,int>(
    kind: #set, access: #protected, read_concurrency: true, write_concurrency: true
);
scores.insert_new("alice", 1);
if scores.lookup("alice") as const score then
    process_score(score);
end
```

Placement: core supplies key/equality/atomic contracts; alloc supplies table storage and data structures; actor/runtime supplies principal-checked table ownership, heir, registrar and nonparking wait integration; std may expose a convenience facade. No ambient OS/heap in core.

## 4. Scala-inspired persistent and transactional references

`Ref<T>`: single-cell atomic modify/get/set for values satisfying Send/Sync rules; user callback must be pure and bounded (may retry). Versioned `TRef<T>` and `atomically { ... }` provide optimistic cross-reference atomic commits. `retry` blocks the *logical task*, not a worker, until relevant version changes. `or_else` supports transactional alternatives. Transaction effect checker must prohibit I/O, actor sends, external FFI, spawning Tasks, `await`, locks and nontransactional writes inside retries. Conflicting transactions retry fairly or return a typed bounded-retry error; STOP/cancellation rolls back safely. An ETS table never pretends a sequential sequence of separate operations is an STM transaction.

## 5. Data-oriented/inverted storage

`InvertedArray<T>` may expose zero-copy, scope-bound column read/write loans and, after separately verified partition disjointness, disjoint row-range loans. Structural operations take exclusive admission whenever resizing/swap-delete can invalidate a view. All handles are (owner,slot,generation) checked; no raw spans escape; parallel column writes use exactly the same suspension/borrow/retirement rules as other shared guards. See Java #1424 / LLVM #658 and the existing inverted-class contract.

## 6. Status and acceptance gates

`src/main/java/dev/oreslang/runtime/OresEtsTable.java` is a **trusted-host reference**, intentionally denied on actor carrier threads. It provides public set semantics: immutable scalar keys/values, per-key atomic insert/insertNew/CAS/take/lookup, bounded entry quota, weak snapshot, and nonblocking mark-closed lifecycle. JUnit tests include 10 competing writers and a 10x1,000 CAS update workload. It is **not guest-source implementation**, not proof of actor-safe nonparking access, not source `Arc<T>`, and not STM.

P0 language/runtime: exact parser/effect/borrow lowering for `Arc<Mutex<T>>` and `Arc<RwLock<T>>`, authenticated logical owner and nonparking acquisition, negative borrow/alias/await tests, Java+LLVM actual guest tests.

P1 ETS: private/protected/public, typed `TableId`, registry, heir/owner STOP transfer, bag/ordered types, atomic integer counter, snapshot semantics, cancellation, memory quotas, no mutable host alias, actor/Task integration; deterministic, 5/10/100-carrier contention and teardown tests.

P2 STM: `Ref` and `TRef` with conflict-retry formal model, multi-key invariants, suspend/resume and rollback proofs across both backends. P3: partitioned ECS/inverted columns and adaptive table sharding.

Never mark the new feature fully shipped until exact source identity and full guest compiler/scheduler tests pass across supported targets.

## 7. Send/Sync auto traits and execution-permit scoped concurrency (P0 proposed)

**Status:** proposed compiler/language contract only. Not yet executable in guest source.

### Auto trait rules

- `Send` is a compiler-proven marker for safely moving an *owned value* to a different M:N Task / native thread execution context. The source loses its ownership after `rt take`. Two mutable aliases are never created by a move.
- `Sync` is a compiler-proven marker that sharing an immutable borrow of `T` across contexts is safe (`&T: Send` iff `T: Sync`). It does not imply concurrent mutation is legal.
- `Sendable` remains separate: actor mailbox transport is subject to frozen/copy/ownership and trust-domain authorization, and is not synonymous with `Send`, `Sync` or `Serializable`.
- Derive `Send` and `Sync` transitively for aggregates and generic instantiations if the full field/capability graph is eligible. Compiler-negative for Rc-style nonatomic reference counting, mutable unsynchronized cells (`!Sync`), thread-affine host resources (`!Send`), arbitrary raw pointers and private actor-state aliases.
- `Arc<T>: Send + Sync` requires `T: Send + Sync`; `Mutex<T>: Send + Sync` requires `T: Send`; `RwLock<T>: Send + Sync` requires `T: Send + Sync`. Treat guard transferability independently and prohibit held guards crossing Task suspensions in v1.
- Do not permit untrusted user code to assert unsafe `Send`/`Sync` implementations by inheriting an empty trait; internal overrides require a trusted audited compiler/backend proof.

### Task capture and scoped borrowing

- Detached `do nb { ... }` captures own moved `Send` values or authorized `Sync` shared allocations; they cannot capture nonstatic stack loans. Validate closure captures transitively, including dynamic methods, FFI, trait objects, resource owners and refcounts.
- Proposed `nb scope { ... }` permits child Tasks to borrow disjoint mutable views or shared immutable views, but the scope cannot finish until *physical* child quiescence, even after cancellation/STOP/panic. Non-overlapping loans are forbidden to escape in returned Task/Future/closures.
- M:N Task continuations can migrate worker threads. Use authenticated logical Task identity and incarnation/region generation; native thread-affine handles remain non-Send.

### ExecutionPermit is an authority, not an independent ownership proof

- `ExecutionPermit<Region, AccessSet>` is a runtime-minted, opaque, noncopyable, nonserializable capability. It must be bound to the concrete region and active borrow graph. Taking `rt borrow mut` of an arbitrary token proves only exclusivity of that token, not of unrelated data.
- Access sets carry read/write effects and field/column/row-range constraints. A split consumes/borrows its parent and yields disjoint child capabilities; reunite only after both child Tasks quiesce and the owner generation still matches.
- Static disjointness enables zero-lock parallel execution; runtime-selected partitions require dynamic disjointness checks and structural mutation fences. Do not mistake `Sync` for lock-free mutable access.
- ECS systems declare typed `reads<Component>` / `writes<Component>` access. The scheduler can run nonconflicting systems concurrently, but overlapping writers must serialize or be rejected. Dynamic aliases, filters, FFI, reallocation and structural changes require explicit handling.

### Illustrative Oreslang (non-executable grammar proposal)

```ores
let Player player = Player(hp: 100);
let Task<void> worker = nb {
  let Player mine = rt take player;
  mine.hp -= 10;
};
// Using moved player here is a compile error.

nb scope {
  let (left, right) = positions.split_mut(midpoint);
  let Task<void> a = nb { integrate(rt take left); };
  let Task<void> b = nb { integrate(rt take right); };
  await a;
  await b;
}
// Parent reclaims access only after both Tasks have physically quiesced.
```

### Required tests

Static positive/negative Send/Sync cases; 5/10/100 Task moves; mutable capture escape; detached vs scoped lifetime checks; cancellation while running; await/select/FFI alias rejection; Task migration; actor heap and isolate denial; disjoint columns and conflicting ECS systems; retired generation invalidation. Demand real Java and LLVM guest-source execution and sanitizer/race tests before enabling.
