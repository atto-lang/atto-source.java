# Actor tickets and suspendable typed match: integration audit

Status: draft, unmerged. This audit records two independently verified source
branches. It is not a claim of combined runtime/compiler conformance.

## Authority chain

A host `SerialExecutionTicket` is issued for exactly one `ActorCell` execution
domain, numeric scheduler task ID, and (when serial) handler admission lease.
The ticket must be checked **before CONTROL capacity reservation**, again under
the actor lifecycle lock at mailbox publication, and finally on the actor's
entered carrier before guest continuation execution. Cancellation/retirement
may race any one check, so producer callback code must remain host-only; the
scheduler terminal CAS and carrier execution-context checks remain required.

The typed-match branch's `SourceTask` owns `ARM_RESULT` by task object
identity and lexical match frame ID. That value must not become a host
scheduler ticket or allow a future producer to execute guest code. The
separate host ticket is the ultimate actor/receive/incarnation authority.

## Concrete provenance hardening

1. Reject a ticket whose actor ID or incarnation execution-domain object
   differs from the destination cell **before** reserving CONTROL headroom.
2. Reject retired/completed tickets before reservation and recheck at
   publication. Late retirement still requires the dispatch-time guard;
   prechecks alone do not close the race.
3. A finished originating receive is a benign retired continuation, never
   permission to borrow its successor's serial lease. A **live foreign**
   receive remains an invalid authority that must not be swallowed.
4. The source scheduler may publish a result only after legal source cleanup
   has settled. The serial actor turn must remain logically reserved until
   that terminal publication or defined cancellation settlement.
5. Guest `defer/finally` upon actor cancellation **has not been proved** to
   execute in all states. A runtime shutdown that simply retires a suspended
   source task can bypass its guest cleanup continuation. Do not treat an
   abstract-model `RunCleanup` step as a Java implementation proof.

## Verified source boundaries

- Serial actor / host ticket: PR #656, its exact-source funded JVM suite,
  actor stop-versus-Future race tests, and separate finite-state TLC models.
- Suspendable typed match: PR #661, exact-source JVM and native AOT/hybrid
  execution fixtures with task-owned, frame-owned `ARM_RESULT`.
- These results apply independently. The branches share merge base
  `4b0ff7dd4063df2906dc2fed0f6b6348b69807c6` but have
  **10 overlapping edited source files** requiring a real three-way merge:
  `Ast.java`, `OresEvalRootNode.java`, `Lexer.java`, `Parser.java`,
  `Token.java`, `ActorRuntime.java`, `CapabilityChecker.java`,
  `RuntimeGarbageCollector.java`, `OwnershipChecker.java`,
  and `TypeChecker.java`.
  Blind file copying or tree overlay would discard language/runtime fixes.

## Required combined guest and formal gates

Build a new merge/test branch that resolves all ten conflicts, verifies
source-tree and Git-blob provenance, and executes an actual Oreslang serial
actor whose typed match suspends inside `await`. Exercise stop-versus-producer
completion (both orders), duplicate callbacks, deferred failure, later
receive admission and actor restart. Check publication-before-cleanup
prohibition, exactly-once completion, safe cancellation, and no actor
incarnation or mailbox ownership borrowing. Run GraalVM JVM/native and LLVM
source differential tests on that **combined** tree.

The bounded Java state explorers and TLA+/TLC invariants are useful safety
checks with deliberate unsafe mutants. They do not by themselves establish
implementation refinement or fairness/liveness; both remain separate gates.

## Integrated native and cancellation audit (2026-10-11)

The stacked Java integration now has executable evidence, distinct from the
original independent branch evidence above:

- [470-blob combined JVM run](https://github.com/zed-pkg-test/java-app/actions/runs/38197030075):
  1,597 Maven tests, zero failures/errors, one skip, actual source serial
  actor with awaited typed match, ordered receive leases and LIFO defer.
- [470-blob GraalVM native run](https://github.com/zed-pkg-test/java-app/actions/runs/38197029979):
  native AOT and hybrid built with **GraalVM 25.3**, verified the source Git
  blobs and executed `tests/fixtures/serial-typed-match-await.ores` with
  exact expected output. Merely building a native-image was not counted.
- A prior run revealed a stop-before-task-retirement race:
  `SerialExecutionTicket.requireAdmissible` classified a matching actor
  ticket as foreign when the actor was already stopped. The fix classifies
  matching revoked liveness as benign retirement, but still treats a
  different actor/incarnation as an authority violation. The corresponding
  real runtime stop/Future race and exact-source JVM run passed.
- GraalVM native guest startup initially failed because
  `Ast$ExprStmt.getRecordComponents()` lacked native reflection metadata.
  The 74 AST record types are now explicitly registered. A new JVM guard
  compares that metadata with the compiled `Ast` records so future AST
  extensions cannot silently break native startup.
- Actor finalization now preserves a source scheduler shutdown exception in
  its terminal result (and suppresses it onto a prior actor failure) rather
  than silently reporting successful `get_done()`.

These tests establish *execution and safety for the covered scenario* only.
They do not establish that the Java interpreter has an actor-authorized
cleanup-only lane for a task cancelled while suspended, nor that every
outstanding `defer` and `finally` executes once before actor termination.
The earlier bounded formal protocol models assume a cleanup transition and
are **not** evidence that actual Java STOP/CANCEL realizes that transition.
Those issues, plus native/JVM/LLVM differential execution for cancellation
and model-to-implementation refinement, remain blockers to merging #691.
