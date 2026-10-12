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

## CONTROL reservation and producer-Future ownership hardening (October 11)

This pass found two remaining transition-ordering defects in PR #656:

1. **Revocation vs saturated CONTROL lane.** The former fast-path
   `taskTicket.mayEnqueue()` check ran before `reserveContinuationSlot()`.
   Retirement could win while the enqueuer was waiting for mailbox accounting;
   a full CONTROL lane would then fail-stop the actor even though the callback
   had already become stale. `SerialExecutionTicket.reserveControlSlotFor`
   now returns an explicit `REVOKED/FULL/RESERVED` result and serializes
   retirement with the host-only reservation callback. Rejected actor IDs,
   old incarnations, and retired tickets never invoke slot accounting.
   A genuinely live, full-lane request may still cause the documented
   fail-stop overload action. The caller must still recheck before publication,
   and the carrier still enforces execution provenance.
2. **Cancellation vs producer-Future adoption.** `enqueueOnCompletion`
   formerly called `ownFuture` before checking the target's origin and
   even after the actor had stopped. A stale target could cancel a
   producer Future it had never legitimately adopted. Its target validation
   and insertion into `pendingOperations` are now both under the **same
   actor lifecycle lock** as stop, making ownership/cancellation ordering
   explicit. A Future legally adopted first is canceled on stop; one
   arriving after stop is untouched.

New JUnit regressions:
- `SerialExecutionTicketTest.controlReservationLinearizesWithRetirementAndRejectsForeignOwners`
- `ActorContinuationFutureOwnershipTest.stoppedActorTargetDoesNotCancelIndependentFutureBeforeControlAdmission`

### Verification status

Production and regression commits were pushed to PR #656. A private,
exact-source mirror was created at
`akrion-sim/akrion-sim-e2e` on branch
`ci/serial-ticket-control-hardening-20261011`, pinning the source
`SerialActorAdmission`, `SerialExecutionTicket`, and `ActorRuntime`
blobs for targeted Java compiler/host-race checks. Its initial Actions
run [38198767279](https://github.com/akrion-sim/akrion-sim-e2e/actions/runs/38198767279)
failed **before runner allocation** (`runner_id=0`, `steps=[]`).
Therefore, these latest changes have **not passed executed CI**.
Previous green runs verify earlier source revisions, not this head.

### Remaining formal and release obligations

A bounded TLC model of ticket origin cannot prove the real atomic reservation:
attach a linearization point or a concrete transition-system refinement to
the source reservation/retire monitor; schedule adversarial stale-Future,
lease-finished, restarted-incarnation, and saturation races. Prove that
`SourceTask` cancellation guarantees each required guest defer/finally
or explicitly fail closed in modes where guest cleanup is not guaranteed.
Reconcile the actual #656/#661 merge tree, run an async typed-match serial
actor through stop/cleanup/restart, and execute JVM, native-image, and LLVM
differential gates before merging either PR.
