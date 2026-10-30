# Mutable state, local ownership, and reentrancy: staged contract

Status: design contract and Java baseline regressions, not a new state-class
syntax implementation or cross-backend safety proof.

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

## Java baseline regression scope

LocalMutableStateBoundaryTest has 13 tests: local scalar and exclusive heap
mutation, lexical loan retirement, synchronous exclusive parameter access,
conflicting local loans, local-reference return/capture, cooperation with a
live read/write loan, live/released guard admission, and one exact-output
guest continuation execution.

Negative cases parse separately before asserting compiler admission rejection:
a syntax failure cannot count as an ownership test pass. Diagnostics must name
the ownership/guard failure. Positive cases and guest execution prevent blanket
rejection from satisfying the suite.

Run with JDK 25 and the repository's GraalVM dependencies:

    mvn -B -ntp -Dtest=LocalMutableStateBoundaryTest -DfailIfNoTests=true test

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
