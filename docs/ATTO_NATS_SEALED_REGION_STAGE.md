# AttoNATS sealed actor state: JVM region stage

This draft keeps the canonical `state { ... }` fields inside
`Ast.ClassDecl.sealedStateFields`, not ordinary actor fields. The compiler
still emits `ACTOR_SEALED_STATE_LOWERING_UNSUPPORTED` until checked critical
source lowering is complete.

## Region and STOP invariants

`runtime/SealedActorStateRegion.java` is a **package-private JVM scalar
storage substrate** for future source-actor lowering. It is **not** a native
per-actor heap, standalone actor executor, guest API, verified borrow checker,
or near-zero-GC solution.

- Allocation requires a live, runtime-minted actor execution context.
  `allocateFromSealedDeclaration` now also requires the **identical**
  `Ast.ClassDecl` object in the immutable `CodeImage` installed on that
  exact running source actor, with the matching selected actor type. A
  separately parsed lookalike actor and a different actor in the same image
  both fail with `SecurityException`. Unqualified actor names must be unique
  within the image. The source layout comes only from
  `Ast.ClassDecl.sealedStateFields`, never ordinary class fields, and
  rechecks literal type, duplicate names and field bounds. These are
  **host/runtime provenance gates**, not a checked compiler borrow proof.
  This stage does not enable source critical syntax or bypass TypeChecker.
- The region stores only 1–64 validated `Long`/`Boolean` scalar fields.
- No field map or mutable array is exposed; the interpreter must consume
  compiler-resolved field slots, never a guest-forged numeric slot index.
- `read` and `write` use the existing `ActorStateLoans(0)` arbiter with
  no queued waits. The loan is tied to actual runtime, actor ID, actor
  execution domain and this physical execution segment. Mutable writes
  require the write view; read-only views cannot write.
- Views are lexical. An escaped view, stale turn, wrong actor, duplicate
  critical acquisition, wrong type or unknown field fails closed. Async
  Futures/CompletionStages returned by a critical callback are rejected. Compiler effect
  analysis must still forbid any suspend or alias escape **before** runtime.
- STOP revokes new admissions and active view authority outside the lifecycle
  lock; existing lexical loans physically retain their backing scalars until
  the `finally` release. Finalization after source-task quiescence closes
  any remaining region and zeros scalar slots.
- The JVM currently uses an ordinary private object array behind actor-
  scoped authority checks. Native isolated heaps/actor frame arenas, safe
  memory reclamation under hostile interruption and cross-backend parity
  still need separate implementation and tests.

## Executable tests and their evidence boundary

`SealedActorStateRegionTest` contains 6 JVM tests, including a real
`ActorRuntime.spawnCodeActor` dispatch path with a **host-provided test
executor** bound to a parsed immutable code image and allocating its state
from the same actor declaration's sealed AST schema. The test
also rejects an identical-looking declaration parsed into a different
code image and an unrelated declaration within the admitted image. The tests check persistent
state through two mailbox turns, a concurrent actor STOP while a sealed
loan is active, and automatic retirement only after physical actor
finalization. Behavior-owned host test regions must be sealed in a turn
and reclaimed after task quiescence, not directly from a message handler. Its Java test executor is **not compiled Atto guest code**.

The exact-head workflow also runs `SealedActorStateAdmissionTest` (6 tests)
and `ActorStateLoansTest` (7 tests) and rejects skipped/missing Surefire
reports. It must run all **19** tests with zero errors, failures and skips
before calling this stage tested. A GitHub job with empty/null steps is only
runner admission failure.

## Remaining P0 to satisfy compiler issue #2332

1. Lower checked `critical` methods and `self.handle_state` lambdas into
   a typed lexical region op; bind read vs write mode to a compiler-generated
   region ticket, with no user-controlled region or execution identity.
2. Authenticate the source-actor invocation against the actor's active
   generation, mailbox turn, run/stop lifecycle and compiler-owned field
   layout. Prohibit async effects, nested loans and source/Java method-ref
   escape during a critical scope.
3. Upgrade storage to independently bounded actor-owned arenas and lifecycle
   cleanup across outstanding tasks/loans; validate under STOP, cancellation,
   hot restart and generation rollover.
4. Run the **actual** AttoNATS
   `tests/guest/actor_state_critical.atto` (exact Git blob) on a trusted
   compiler with nonzero executed tests. Require mailbox read/write/result,
   negative guest fixtures, source SHA/tree and independent review.
5. Only after this gate may AttoNATS join NIO2 sessions and shared
   `SubjectRouterActor` state. A host-side Java model or Rust oracle is
   never sufficient for the broker release gate.
