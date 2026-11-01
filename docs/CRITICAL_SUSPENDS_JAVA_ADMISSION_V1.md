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

Host runtime `dev.oreslang.runtime.CriticalGate` has immediate try-enter,
FIFO bounded logical tickets, cancellation of queued tickets, stale
generation rejection and retirement. It is **not wired** to the evaluator.
Critically, the scheduler must never run callbacks/guest code from
`CriticalGate.release`; it must validate task, actor, tenant and generation
identity before dispatch.

## Required before enabling execution

1. Establish stable gate identity per protected resource/instance/module and
   tenant/isolate, rather than global AST text or JVM-global locks.
2. Implement scheduler `OresFuture` completion with cancellation/queue
   retraction and fair wakeups; contended critical entry suspends its source
   continuation but does not block a carrier thread.
3. Prove body calls transitively non-suspending across function values,
   overloads, generic calls, virtual dispatch and native/host FFI.
4. Implement a lexical guard that releases on return, break, continue,
   trap, cancellation and actor teardown; test return-flow continuation.
5. Prevent reentrant actor handler admission while suspended without holding
   a physical mutex across await.
6. Run executable source cases with an exact-head compiler hash and recorded
   GitHub Actions steps. Source parsing and host tests are not guest evidence.
7. Migrate legacy Java `async` tests and source semantics deliberately.

Shared target API and 24-source manifest:
`oreslang-common-source/contracts/v1/critical-suspends-v1/`.

Current GitHub workflow jobs have reported `steps=null`, which is a
runner admission failure, not a JUnit or compiler validation receipt.
