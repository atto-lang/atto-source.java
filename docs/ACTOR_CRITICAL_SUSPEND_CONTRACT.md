# Actor critical sections and persistent state — synchronization contract

**Status (October 11, 2026): proposed guest-language semantics; host
non-suspending native-map prototype hardened in this branch. Not implemented
source-level suspendable critical lowering.**

## Scope and ownership

Actor state is per **actor instance/incarnation**, not per class, request or
physical worker thread. A reused actor preserves persistent state across
mailbox turns until actor retirement. Native persistent arena slots survive
turns; scratch slots do not. Java/Graal wrappers and ordinary Java Map objects
remain GC-managed. Do not infer physical GC independence from an actor arena.

All paths to a designated protected state field must go through one **sealed
actor-instance state capability**, not just one conventionally named method.
A `critical` method does not make public aliases to an ordinary Map safe.
For state declarations, prefer a sealed compiler-known `state { ... }`
schema and `self.handle_state(|s| ...)` access. A host ActorPersistentI64Map
already gates read/write through a short-lived Access object; arbitrary
`Map<K,V>` source lowering remains unimplemented.

Existing Java prototype reserves `handle_state` as a sealed intrinsic
and rejects user-declared actor methods with that name. The design below
**does not yet admit** `critical suspend handle_state` as a user method.
A future source syntax decision must reconcile this intentionally, not
accidentally override the intrinsic.

## Proposed modes (not currently compiler-executable)

* `critical update_state(...)` is **non-suspending**. If the same logical
  continuation already owns the actor-instance gate, a synchronous nested
  entry may be allowed with properly fenced nested access loans. If the gate
  is free, enter and return synchronously. If held by a different continuation,
  it must **fail fast** with a typed busy result/error; never park or
  silently suspend an ostensibly synchronous caller.
* `suspend critical update_state(...)` (canonical order to be finalized) is
  a **guaranteed suspension boundary**, even if the gate is free. Enqueue the
  request in a bounded, fair logical gate queue, save the continuation and
  return the physical carrier; the scheduler later admits and resumes it.
  Queue overflow and cancellation have explicit outcomes. The guarantee is
  logical suspension, not a thread sleep, blocking monitor, or necessarily a
  different OS carrier.
* `try critical` (possible future syntax) may provide a distinct nonthrowing
  `Option`/Result-shaped nonblocking admission API. Do not overload
  `critical` to invisibly change a function's synchronous return type.
* Ordinary actor mailboxes are currently single-flight, so uncontended
  `critical` incurs no user-visible suspend. Different tasks/continuations
  sharing the actor state still require serialized admission. Multiple
  critical methods must share the same per-field or per-actor gate.

A compiler must check explicit/transitive `suspend` effects and forbid a
borrow-like state `Access` from escaping, reaching an unknown callee, or
crossing `await`, select, reduction-yield, FFI, cancellation or any other
resumption boundary. `suspend critical` yields **before** it obtains a
state loan; it must not park or yield while holding that loan. An explicitly
suspendable transaction across awaits would require release/revalidation and
application-level conflict semantics, and is out of scope.

## Safety invariants and failure behavior

1. A critical gate belongs to a semantic actor incarnation and to a logical
   continuation for reentrancy; actor ID alone is insufficient to identify
   distinct tasks on the same actor. Physical carrier/thread IDs are not
   continuation identities.
2. No mutex or JVM monitor may be held across suspension; no host carrier
   is permitted to wait for an application critical gate.
3. State capability and any projected mutable reference remain lexical and
   non-sendable. All reads, writes, iteration, reference return and callbacks
   require authorization. No mutation via a leaked Map alias is allowed.
4. Cancellation/throw/raise always run scoped cleanup; panic goes to the
   supervisor's priority CONTROL path and cannot be intercepted by the actor.
   Cleanup is exactly once; waiters are woken or cancelled exactly once.
5. FIFO ordering is the initial fairness policy; bounded queue capacity,
   cancellation removal, actor termination revocation and no-new-admission
   after shutdown are required before shipping `suspend critical`.
6. Borrow checking must also cover compiler-inserted scheduling/reduction
   points. If the actor owns a critical loan, no other continuation can be
   admitted to mutate it; a source `await` while loaned is rejected.
7. Native arena generations and actor-incarnation IDs prevent stale handles
   after retirement/reuse. Physical native release waits for pinned loans,
   while logical access is revoked immediately.

## Related implementation branches and reconciliation

- [Java critical callable PR #577](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/577)
  has a bounded logical `CriticalGate`, FIFO tickets, source evaluator
  integration and cancellation work on a **different branch**. It is not
  yet merged into this actor arena branch. The host-only `ReentrantLock`
  below is temporary and **must not become a second production critical
  gate**. Source state admission should ultimately use the verified
  continuation-owned `CriticalGate`, with a per-state/per-actor authority.
- [Guaranteed suspension follow-up #632](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/632)
  updates #577's previously inline uncontended `suspend critical` fast path
  to use the scheduler's already-completed-future handoff. This is separate
  from borrowing actor-state memory: a logical invocation ticket may survive
  suspension, but a raw mutable Access/loan may not.
- Merge/rebase and re-run executable cross-branch tests before permitting a
  source critical callable to access arena-backed persistent state. The
  duplicate gates cannot simply be nested without proving ordering and
  cancellation/teardown behavior.

## Current implementation and release gates

- `ActorPersistentI64Map.critical` and `close` use `tryLock`, not
  carrier-parking synchronized admission. Reentrant nesting is host
  **same-thread synchronous** only; this is not a scheduler/continuation gate.
- `ActorStateContract` now rejects source `await`, select, runtime ops,
  and calls lacking trusted non-suspension effects while a state callback owns
  its scoped access. It remains conservative rather than manufacturing proof.
- Java/Graal source `critical` is currently parsed as an annotation and
  then intentionally rejected in `TypeChecker`; no guest-runtime support.
- Real `suspend critical`, data-plane actor continuation ownership, bounded
  logical queues, no-GC Graal proof, cancellation/unwinding integration,
  native-image tests and Java/LLVM guest-source conformance are outstanding.

**Acceptance tests before removing this restriction:** uncontended fast
critical; busy non-suspending fail-fast; always-yield suspend critical; FIFO
fairness; same continuation nesting versus distinct continuation contention;
forced carrier migration; cancellation at every gate transition; panic and
termination; loan escape negatives; no carrier parking under contention;
arena teardown with outstanding loans; and exact-source Java+LLVM parity.
