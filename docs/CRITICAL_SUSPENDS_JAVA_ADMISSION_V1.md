# Java/GraalVM critical source admission — v1 (2026-11-01)

Status: **restricted actor-only guest lowering is authored, but NOT execution-verified**. `try critical`, free-function critical and effectful critical bodies remain denied. The Java Actions job has not executed its test steps.

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

The TypeChecker permits the restricted scalar `critical {}` body in a
`suspends` actor instance method, after bounded effect preflight and ordinary
type checking. The OwnershipChecker then checks the body and publishes an
identity-and-epoch-bound permit for admitted critical AST nodes. All other
critical scopes, including `try critical`, are rejected. The synchronous
evaluator denies critical source outright. The suspension-aware `SourceTask`
evaluator requires the permit and actor-owned gate, but this source path has
not been established as working by executed compiler/guest CI. The permit
lookup itself validates ownership epoch and AST identity; it does not
independently certify that every caller ran TypeChecker first. Treat
host-supplied/untyped AST entry points as a separate security audit gate.

Host runtime `dev.oreslang.runtime.CriticalGate` now has `requestAwaitable()` returning a runtime-owned `OresFuture<Ticket>` in addition to immediate try-enter, FIFO bounded logical tickets, cancellation, stale-generation checks and retirement. It settles the next ticket **only after unlocking** its bookkeeping mutex.

The suspension-aware `SourceTask` evaluator now has a restricted actor-only
critical lowering path: it awaits a `CriticalGate` future through the
scheduler, registers cancellation cleanup, checks the current actor/runtime
incarnation after admission, and releases the ticket on completion, return
or failure. `criticalGuestLoweringAdmitted(...)` checks an identity-bound
ownership permit and returns true only after that check; it is **not** a
constant deny. This candidate still needs exact-head compilation, runtime
cancellation/cleanup evidence, and a proof that untyped host AST entry
points cannot mint equivalent authority. Future settlement must not execute
guest callbacks; actor/task/generation fencing remains mandatory.

## Required before treating the restricted lowering as verified or widening admission

1. Retain the authored per-ActorCell incarnation gate and recheck it after
   scheduler resumption. Nonactor protected resources require a separately
   designed, identity-bound capability; no per-AST or JVM-global gate may
   authorize them. Independently test tenant/isolate boundaries.
2. Verify the now-authored scheduler `OresFuture` bridge, cancellation/queue
   retraction and fair wakeups under actual JUnit and guest workloads; ensure
   no Task cancellation can leak an already-granted ticket.
3. Prove body calls transitively non-suspending across function values,
   overloads, generic calls, virtual dispatch and native/host FFI.
4. Verify the authored lexical cleanup on return, trap and cancellation,
   including forced shutdown, carrier migration, early-return and double
   release. Widening the statement subset to loops/break/continue requires
   additional control-flow release tests.
5. Prove nonreentrant actor handler admission while a critical ticket is
   queued, without holding a physical mutex over suspension. Authenticate
   both TypeChecker and OwnershipChecker provenance for every admitted AST,
   not only the current ownership epoch; reject host-injected/untyped ASTs.
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
carrier. This is an authored safeguard, not evidence of successful guest execution.

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
executed steps. Restricted guest source lowering is present but has no
stepful compiler/guest receipt. Nonactor, `try critical` and effectful
critical scopes remain intentionally denied.

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

The candidate native Oreslang guest sources are
`examples/critical-actor-checked-v1.ores` (expected `42`) and
`examples/critical-actor-early-return-v1.ores` (expected `returned`, followed
by actor STOP/physical cleanup), exercised by the
`CriticalActorGuestExecutionTest` JVM/Graal polyglot-context test. Shared
parity copies are recorded in the common-source v1 contract and match both
Java guest bodies. This is a `.ores` guest execution test, **not** a
GraalVM Native Image AOT binary build/test.

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

## Exact-Program typing provenance and partial-publication hold (2026-11-02)

The restricted actor-only critical candidate now requires a **successful
independent TypeChecker proof** in addition to the ownership epoch and exact
critical AST / Program identity. The typing ledger uses weak identity keys,
not value-based Program equality: a parsed clone never inherits admission.
Every published critical permission also records the exact successful
typing generation alongside its ownership epoch. A new *successful* type
analysis cannot resurrect an older ownership permit: a new ownership pass
must explicitly reauthorize the retyped Program. Failed/restarted/superseded
type checks revoke the older proof; the ledger publishes success only after
the complete type-analysis diagnostic result is constructed. Linked-metadata
type checking follows the same rule.

A public direct ownership check can inspect an untyped AST, but if it sees
critical nodes it now refuses to publish the shared Task ownership epoch
before an exact-Program typing proof exists. Critical permission publication
and runtime use enforce typing again, so an earlier ownership epoch cannot
resurrect execution after a later failed type recheck. The publisher also
requires an opaque authority whose constructor is private to the
OwnershipChecker, so ordinary host callers cannot pass a hand-chosen
critical AST set through the public publishing method. This is JVM API-level
hardening, not a sandbox against malicious reflection or native access.

Ownership-only checks on programs **without** critical statements remain
supported and do not need a new typing proof. Seven authored regression
cases cover untyped ownership-only admission, equivalent untyped ASTs,
ordinary typing failures, failed linked-metadata rechecks, successful
rechecks with stale ownership epochs, ordinary noncritical ownership
compatibility, and nonconstructible publisher tokens.
These tests have **not** run in stepful exact-head CI; critical source
remains a draft feature.

## Adversarial gate handoff and CI-mirror policy (2026-11-01 follow-up)

Fixed an additional actor-retirement race in `CriticalGate.release(ticket)`:
the first version removed the promoted waiter's `OresFuture` from
`admissions` *before* settling that Future outside the metadata lock. An
independent `retire()` between these operations would no longer find the
promoted-but-unsettled Future to fail. The revised release keeps the Future
in the identity ledger through terminal completion and removes its exact
key/value afterward in a `finally` block. Concurrent ticket cancellation
and actor retirement can still retract or fail that admission; no caller
runs guest code while gate bookkeeping is locked.

New authored JUnit tests exercise a deliberately blocked promoted-Future
notification during retirement and 128 adversarial release/cancel/retire
races. These tests are **not yet executed** against the changed branch.

For CI capacity, the private carrier
`gha-indie-worker/gha-indie-worker-docs` (numeric GitHub repository ID
`1351003564`) ran a source-free Java toolchain check at
[Actions run 41391572866](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/41391572866):
**success, five nonempty executed steps**, Java 25.0.5, Maven 3.9.15.
No private Oreslang source was copied. This is runner/toolchain evidence,
**not** an Oreslang compile or guest pass.

An exact PR-head cross-organization source mirror is **blocked by the
repository's checked-in private-source export policy**:
`docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` requires a protected source
`main`, reviewed numeric destination allowlist and independent security-owner
approval. The last recorded source `main` check reported `protected=false`;
current independent protection verification is unavailable to the connected
GitHub App (403). The checked-in
`.github/private-ci-approved-repositories.txt` has no approved entries.
Do not bypass with GitHub connector blob/tree writes. The concrete
candidate IDs, source head/tree and capacity receipt are recorded on
[governance issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962).
