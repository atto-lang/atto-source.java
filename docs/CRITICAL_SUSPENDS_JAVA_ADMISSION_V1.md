# Java/GraalVM critical source admission — v1 (2026-11-01)

Status: **parser and safety-preflight implementation; guest execution NOT enabled**.

This PR now preserves `critical { ... }` as `Ast.CriticalStmt`, and
`try critical { ... } else { ... }` as `Ast.TryCriticalStmt` rather than
reducing either to an unprotected ordinary block or lexical `try`.
A `try critical` without an explicit fallback is a parser error.

`CriticalEffectPreflight` is bounded (128 nested levels, 4096 inspected
nodes) and deliberately conservative: it permits a restricted scalar subset
and rejects await, calls, runtime/host FFI, channel/select, task launch,
deferred callbacks, nested guards, dynamic getters/setters and unknown
expression kinds. A pass does **not** prove a call effect-safe; calls are
rejected until transitive typed effect closure is implemented.

The TypeChecker verifies that `critical {}` requires a `suspends`
callable for a potentially queued entry, but then **rejects all critical
source** until scheduler-owned ticket acquisition and lexical guard unwind
are implemented. `try critical` also rejects until immediate ticket entry
and fallback execution are verified. The OwnershipChecker independently
rejects both AST nodes. Both the synchronous evaluator and suspension-aware
SourceTask evaluator reject them explicitly, so skipping normal type
checking cannot silently turn a critical body into unguarded execution.

Host runtime `dev.oreslang.runtime.CriticalGate` now has `requestAwaitable()` returning a runtime-owned `OresFuture<Ticket>` in addition to immediate try-enter, FIFO bounded logical tickets, cancellation, stale-generation checks and retirement. It settles the next ticket **only after unlocking** its bookkeeping mutex.

The suspension-aware `SourceTask` evaluator now contains a staged critical lowering path that would await that future through `OresScheduler`, register heap-owned cancellation cleanup, and release the ticket on body completion/return/failure. The path is **not guest-authorized**: `criticalGuestLoweringAdmitted(...)` deliberately returns false, as the type and ownership passes have not issued identity-bound proof of safe protected resource/actor ownership. Host AST embeddings must also fail closed.
Critically, the scheduler must never run callbacks/guest code from
`CriticalGate.release`; it must validate task, actor, tenant and generation
identity before dispatch.

## Required before enabling execution

1. Establish stable gate identity per protected resource/instance/module and
   tenant/isolate, rather than global AST text or JVM-global locks. The
   current staged nonactor path uses per-evaluator lexical-site identity,
   which is insufficient to authorize arbitrary shared state. Actor contexts
   are explicitly denied until an actor-owned incarnation fence is wired.
2. Verify the now-authored scheduler `OresFuture` bridge, cancellation/queue
   retraction and fair wakeups under actual JUnit and guest workloads; ensure
   no Task cancellation can leak an already-granted ticket.
3. Prove body calls transitively non-suspending across function values,
   overloads, generic calls, virtual dispatch and native/host FFI.
4. Implement a lexical guard that releases on return, break, continue,
   trap, cancellation and actor teardown; test return-flow continuation.
5. Prevent reentrant actor handler admission while suspended without holding
   a physical mutex across await; bind each gate to actor-generation state
   and issue a checked, nonforgeable AST admission proof before enabling guest execution.
6. Run executable source cases with an exact-head compiler hash and recorded
   GitHub Actions steps. Source parsing and host tests are not guest evidence.
7. Migrate legacy Java `async` tests and source semantics deliberately.

Shared target API and 24-source manifest:
`oreslang-common-source/contracts/v1/critical-suspends-v1/`.

Current GitHub workflow jobs have reported `steps=null`, which is a
runner admission failure, not a JUnit or compiler validation receipt.

## Actor-cell gate hardening (follow-up)

The actor-owned runtime now lazily mints one `CriticalGate` for each **ActorCell**
incarnation, not one gate per shared AST or carrier thread. The runtime-only
`ActorRuntime.currentActorCriticalGate()` method requires the exact
authenticated current actor identity and refuses admission after STOP. A
replacement actor receives an independent gate.

The staged `SourceTask` critical path now checks the actor runtime, actor ID,
and exact current actor gate again *after* its awaited ticket becomes ready.
An old ticket cannot authorize a replacement actor or arbitrary resumed
carrier. This is separate from the still-closed source execution gate.

Actor finalization calls `CriticalGate.retireIfQuiescent()` under the actor
lifecycle monitor **after** existing physical source-task quiescence gates.
The retirement operation atomically requires no owner/queued ticket/pending
admission and marks the gate retired under its own metadata mutex. If a
ticket survives, actor finalization is quarantined rather than declaring the
actor heap safely reclaimable. A host reference to the old gate cannot
sneak in after a separate idle check.

New JUnit cases cover identity across messages, distinct actors with the same
behavior source, reincarnation/old-ticket fencing, retirement races and
already-granted ownership winning over late cancellation.

**Evidence remains unverified**: GitHub Actions jobs have reported zero
executed steps. Source guest execution is still held closed pending exact-AST
checked ownership/effect admission and non-suspending-body native proofs.

## Restricted actor critical source lowering — candidate (2026-11-01)

The source compiler now admits only `critical { ... }` within a
`suspends` **actor instance method**, and only after all these checks:

1. `CriticalEffectPreflight` proves the body is in the restricted lexical
   scalar subset (no calls, host/FFI, await, task, channel, member getter or
   setter, dynamic dispatch, nested critical, defer or unknown effects).
2. `TypeChecker` checks every nested body statement and expression against
   the enclosing actor method's types and return contract.
3. `OwnershipChecker` checks the body and stages the exact critical AST node
   only while analyzing a suspending actor method. Detached Tasks and
   escaping lambdas explicitly do **not** inherit that authorization.
4. After the complete ownership pass succeeds, it publishes an
   `Ast.CheckedCriticalBlocks` permit keyed by exact Program identity,
   exact AST node identity and the current `CheckedTaskCaptures` epoch.
   A recheck, reconstructed/rewritten critical AST or failed ownership check
   cannot reuse old permission.
5. `SourceTask` requires that permit, then acquires the actor incarnation's
   gate, awaits through the scheduler if queued, revalidates actor/runtime
   incarnation after resumption, and releases the ticket on lexical completion
   or authenticated cancellation cleanup.

The candidate native Oreslang guest source is
`examples/critical-actor-checked-v1.ores`; it must emit `42` under the
`CriticalActorGuestExecutionTest` JVM/Graal test.

**Not admitted:** non-actor/free-function critical, critical declaration
modifiers, `try critical`, direct actor-state field mutation and any
effectful calls or FFI inside critical. These remain separate contract
milestones; this first scalar-only gate primarily proves scheduler and
compiler mechanics, not full protected actor-state semantics.

**Not verified:** GitHub Actions continues to show runner-admission
`steps=null`. The candidate source implementation and guest test have been
pushed, but neither compilation nor actual runtime results are established.
Do not mark this as a portable feature or merge without exact-head
stepful Java guest evidence, then LLVM/.NET/Wasm/Cranelift parity.
