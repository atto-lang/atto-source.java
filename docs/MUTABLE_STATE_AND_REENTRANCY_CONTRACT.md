# Mutable state, local ownership, and reentrancy: staged contract

Status: staged design contract plus concrete Java nested-mutex admission hardening
and baseline regression tests. This does not implement state-class syntax,
actor reentrancy, lock-order inference, zero GC, or cross-backend safety proof.

## Language decision

Local mutable variables, temporary builders, and exclusively owned mutable
objects remain legal. Persistent shared mutation should be encapsulated behind
state operations and injected access capabilities. Allocation location is an
optimization, not an access permission.

A stack slot can be shared through an escaping reference or scoped task. A
heap object can be exclusively owned and require no locks. Suspension can move
locals into continuation storage. The same lifetime and alias-exclusion rules
apply to all three cases.

No new keyword is introduced here. Existing classes, ownership operations,
Mutex/SharedMutex, and sealed actor state are the starting mechanisms. A future
state-class feature must explicitly define construction, access interfaces,
deep mutability, inheritance, serialization, and lowering before enabling syntax.

## Access rules

| Storage/access | Required proof | Synchronization |
| --- | --- | --- |
| Task-local mutable scalar | Lifetime and no conflicting live loan | None |
| Exclusively owned mutable object graph | Unique ownership; no unsafe alias escape | None |
| Local shared read loan | Owner outlives loan; writes excluded | None |
| Local exclusive loan | No conflicting reader/writer; owner outlives loan | None |
| Actor-owned state | Sealed read/write access capability and actor scheduling contract | Existing actor mechanism |
| Shared mutable state | Every relevant alias governed by a checked capability | Atomics, partition locks, or invariant-level lock |
| Immutable snapshot | Deeply safe contents or independent owned copy | None after safe publication |

A binding being fixed does not establish deep immutability. Injection does
not establish Send/Sync safety and must not confer authority to bypass the
container's methods. Read-only and mutation interfaces should be distinct.
An operation spanning several fields must protect their joint invariant;
field-level locking alone is not enough. Cross-container atomicity requires
explicit coordination.

## Local borrow boundaries

The baseline permits local mutation and synchronous borrowed operations.
A local reference must not escape via return, fields, closure capture,
channels, or deferred tasks without an independently verified scoped-lifetime
protocol. Transfer, copying, or publication must not launder a loan into ownership.

The current conservative Java checker rejects ordinary live borrows at
explicit cooperation/suspension. This change does not relax that rule.
Future private-frame borrowing across suspension requires stable storage,
proven task ownership, valid lifetime, no external alias, cancellation cleanup,
and physical task-retirement checks. Pinning alone is insufficient.

The baseline regression cases intentionally include both stack-candidate
scalars and ordinary owned class instances. They make no claim about emitted
stack allocation, allocation counts, escape-analysis success, or zero GC.

## Proposed reentrancy contract

User-defined lock acquisition and release, explicit rt cooperate, and actual
suspension are candidate actor admission boundaries. Acquisition is a potential
boundary even without contention. This is a proposed semantic extension;
current Java serial actors retain their logical receive turn across scheduler
cooperation. No scheduler behavior is changed by this patch.

A recognized lock must connect protected data to non-escaping access capabilities.
An arbitrary method named lock is not a synchronization proof.

- Reentrancy may happen before access acquisition and after release.
- A contended acquisition suspends only when no incompatible capability is held.
- No automatic unlock/relock is allowed inside a protected operation.
- Lock ownership belongs to the handler/task, not merely the actor identity.
- Nested acquisition requires a proven order or an ordered multi-resource API.
- Unknown callbacks and indirect suspension require transitive effect checks.
- Compiler budget checks preserve held capabilities and the actor turn. They
  must not silently introduce application-visible interference.
- Reentrancy opportunities do not promise a scheduler switch or starvation
  freedom. Fair admission, cancellation, and priority behavior need runtime tests.

## Nested lock admission: implemented Java fail-closed rule

The Java ownership checker now rejects attempting a recognized `Mutex` or
`SharedMutex` acquisition (`lock`, `try_lock`, `lock_async`, `with_lock`,
`recover`) while an acquired lexical `MutexGuard` remains live or an inline
mutex critical callback is active. This is a conservative restriction, not
a general deadlock proof: an alias of an existing mutex can otherwise create
a self-cycle even when a second receiver has a different variable name.
It leaves non-nested sequential acquisitions legal. A future ordered
multi-lock primitive must validate resource identity, total ordering,
cancellation, and exception safety before lifting this restriction.

Ten added `MutexLockOrderAdmissionTest` cases cover overlapping distinct
and identical handles, `try_lock`, `lock_async`, and positive sequential
release/acquire. Source parsing is required before negative ownership checks;
the new cases are authored but not yet JDK/Maven-executed.

## Memory-model requirements (not yet implemented or measured)

* LLVM: no tracing GC in guest code. Borrow and lock authority is checked at
  compile time and erased from emitted code when possible. Owned local mutation
  uses stack/SSA storage if it does not escape; independently owned heap graphs
  use deterministic arena/region cleanup. Owning a region is not necessarily
  an allocation, and nested cycles must not leak beyond a torn-down region.
* Java/GraalVM: minimize managed allocations and collector pressure through
  actor/container-owned native/off-heap arenas for suitable bounded data,
  while retaining checked handle generations and safe native reclamation.
  JVM and Native Image objects still have managed runtime semantics; no
  blanket zero-GC or off-heap safety guarantee is made.
* Borrow lifetimes are logical, not derived from stack/heap location. A
  suspension can move locals into continuation storage. Concurrent readers,
  cancellation, and task/actor retirement must all be resolved before memory
  reuse, and reuse must prevent stale-generation access.
* Protected state operations must avoid mandatory snapshots, per-access
  guard allocations, mandatory heap boxing, and hidden allocation on
  hard-real-time paths. Publication of immutable snapshots is optional
  and cannot expose internal mutable aliases.

Evidence gates: native emitted-IR or assembly inspection, source-level
accept/reject conformance, deterministic teardown/ABA and cycle tests,
allocation counting under sustained actor traffic, and hard-RT
worst-case admission/dispatch timing when applicable. Compiler admission
alone cannot establish no-GC or allocation-free execution.

## Incremental state-container implementation using existing classes

The first implementable shape is an ordinary owned class with a coherent
invariant and an explicit `mut self` method, not an assumed implicit global
lock. A separate service may receive `borrow mut InventoryState` for a
synchronous state operation; the borrower cannot keep or publish that loan.
Read-only injection uses `borrow InventoryState` and cannot invoke an
exclusive mutation operation. Coherent operations such as updating
`available` and `reserved` together belong in one method; a future
shared-mode wrapper must protect the entire invariant.

`InjectedMutableStateContainerTest` now exercises: exclusively owned
two-field mutation across cooperation without a mutex; borrowed injection
into a mutating method; rejection of a read-only borrow attempting that
method; private-field read/write exclusion; read-only injected scalar
observation; and exact-output Graal guest executions of both inline
validated reservation and the checked-in native source
`examples/state-container-injection.ores`. Overdraw and zero/negative requests
must leave the joint invariant intact. These are admission tests; no actor reentrancy, automatic injection
framework, partition optimizer, or shared-state synchronization is implied.

A next-step shared container should be an opaque owning handle with distinct
reader/writer authorities, deterministic release, and explicit access
effects. It must not copy mutable fields out or expose escaping internal
references. Preserve the cheap owned-class path when the region never
escapes its task/actor.

Both guest oracles now require `7|3|true|false|false|false`, representing
a successful reservation of three units followed by over-capacity, zero,
and negative refusals with the joint `available/reserved` invariant preserved.
This is **sequential invariant preservation**, not an atomic concurrent
transaction; shared-thread admission still needs region-level protection.

## Source-first implementation milestone

`examples/state-container-injection.ores` is the first canonical native
Oreslang implementation of the encapsulated-container pattern. Its two
mutable fields are private; public operations expose immutable scalar
observations and a `mut self` reservation operation that validates
before writing related fields. A checkout function receives a
`borrow mut` handle and a reporting function receives a read-only
`borrow`. Neither function receives ownership of the container.

This adds no mandatory proxy object, Java collection, per-operation heap
guard, GC wrapper, reflection, or runtime DI registry. A compiler may keep
exclusively owned scalar state in SSA/stack storage, and a region-owned
aggregate may be allocated in an explicit arena if it escapes.
That optimization and real codegen require separate proof and metrics.

The example is intentionally not yet a synchronized shared state region:
reusing one instance across worker threads/actors requires separately checked
`Send`/`Sync` capabilities and invariant-wide protection. No implicit
actor reentrancy has been enabled.

## Java baseline regression scope

LocalMutableStateBoundaryTest has 13 tests, plus ten MutexLockOrderAdmissionTest cases and eight InjectedMutableStateContainerTest cases: local scalar and exclusive heap
mutation, lexical loan retirement, synchronous exclusive parameter access,
conflicting local loans, local-reference return/capture, cooperation with a
live read/write loan, live/released guard admission, and one exact-output
guest continuation execution.

Negative cases parse separately before asserting compiler admission rejection:
a syntax failure cannot count as an ownership test pass. Diagnostics must name
the ownership/guard failure. Positive cases and guest execution prevent blanket
rejection from satisfying the suite.

Run with JDK 25 and the repository's GraalVM dependencies:

    mvn -B -ntp -Dtest=LocalMutableStateBoundaryTest,MutexLockOrderAdmissionTest,InjectedMutableStateContainerTest -DfailIfNoTests=true test

Then run the existing ownership, mutex, actor-state, and source-cooperation
regressions, full verify, and Native Image gates. No skipped-only or zero-step
workflow is evidence of success.

At authoring, the local host has JDK 17 and no Maven. These new compiler/guest
tests have not been executed locally.

## Backend implementation order

1. Establish executed Java baseline results and reconcile existing borrow-escape
   work, including draft #2098, rather than duplicating it.
2. Add explicit access/effect metadata for encapsulated state operations, initially
   with conservative invariant-level exclusion.
3. Implement checked user-lock admission boundaries and actor scheduling changes
   together; preserve existing serial-actor ordering until explicitly selected.
4. Prove disjoint regions/partitions before narrowing exclusion; never infer safety
   from different field names alone when references can alias.
5. Execute the same source-level acceptance/rejection and runtime traces on LLVM,
   .NET, WASM, and Forge. Unsupported safety features must reject executable
   admission rather than be reported as parity passes.

Additional gates include hidden nested loans, imported/generic state graphs,
foreign callbacks, multi-container transactions, cancellation while queued,
read/write fairness, actor request/reply cycles, and memory publication.
