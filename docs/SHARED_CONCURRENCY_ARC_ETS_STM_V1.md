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

`core` contracts: `Sendable` (move or share across execution domains), `Sync` (read-only shared access is safe), atomic ordering, borrow lifetimes and zero-allocation polling types.

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
