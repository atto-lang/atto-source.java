# Durable actor development loops

Status: **the Java/Graal V1 vertical slice is implemented: deterministic reducer, crash-safe SQLite journal/outbox, atomic receipt+outcome commits, ActorRuntime coordinator, capability-checked activity workers, authenticated and restart-durable GitHub CI ingress, durable supervisor accounting, isolated UI projection fan-out, and a typed workflow graph with ordered durable transition replay. Guest-language single-state actor integration remains gated on the canonical actor state-loan work.**

This document defines the first Oreslang architecture for long-running actor
workflows whose execution can be projected into UI nodes while surviving
browser disconnects, duplicate webhooks, process restarts, and AI/CI retries.

The first concrete workflow is an AI development loop:

1. a human or ticket connector supplies a goal;
2. a coding actor produces a patch;
3. a Git actor pushes an exact commit;
4. a shared GitHub webhook ingress actor authenticates and correlates CI events;
5. a test/evaluation actor evaluates the exact expected commit;
6. failure produces a structured bug report and loops back to coding;
7. success waits for an explicit approval before terminal completion.

## Architectural rule: durable actor first, UI projection second

A durable actor is the authoritative business/execution entity. A browser node,
timeline row, inspector pane, CLI view, or notification is a projection of that
actor identity. Closing a browser must not cancel the actor, lose a durable
wait, or change workflow semantics.

Do not create a server actor for every visual atom. Create durable actors for
independent execution/lifecycle units and project their state into one or more
UI components.

## V1 actor topology

One development run should eventually use the following actor roles:

- DevLoopActor: durable coordinator for one development task.
- CodingActor: bounded AI/code-edit activity.
- GitActor: commit/push activity using an idempotency/effect key.
- GitHubWebhookActor: shared authenticated ingress, not one listener per run.
- TestActor / CIActor: exact-revision test evidence acquisition.
- EvaluationActor: checks test evidence and goal/acceptance criteria.
- BugReportActor: emits structured feedback for the next coding iteration.
- ApprovalActor: human/automation approval gate.
- WorkflowSupervisorActor: cancellation, escalation, restart and retry policy.
- WorkflowViewActor: optional projection publisher for UI clients.

The Java reference backend now contains DurableDevLoopV1, a deterministic
state-machine kernel intended to sit inside DevLoopActor. It deliberately
does not perform side effects.

WorkflowJournalV1 is the executable reference for the journal/outbox boundary.
It independently replays and verifies every supplied reducer transition before
accepting it, atomically records transitions and effect origins, leases pending
effects to workers, and enforces the lease deadline again when a worker reports
completion or known-nonexecution. Expired claims become AMBIGUOUS rather than
being blindly retried. Claim tokens are opaque random-nonce capabilities bound
to run, effect, attempt and global claim number; persisted claim structure is
revalidated on restore. Terminal transitions mark older not-yet-started effects
ABANDONED and revoke retry authority on pre-terminal claimed/ambiguous work.

SqliteWorkflowJournalV1 is the persistent reference adapter. It uses WAL,
synchronous=FULL and BEGIN IMMEDIATE transactions, reconstructs state by
deterministic replay on every reload, rejects tampered lifecycle data, and
invalidates a handle if rollback or recovery cannot be proven. Real
child-process kill tests cover both sides of the SQLite COMMIT boundary.

## State ownership

A concrete Oreslang actor keeps persistent guest-owned instance data in its one
state { ... } schema. The eventual DevLoopActor state is expected to include
at least:

- stable run_id;
- current phase;
- current iteration and maximum iteration budget;
- original goal / ticket reference;
- current patch reference;
- exact expected commit object id;
- latest CI/test report reference;
- latest evaluation failure reference;
- latest structured bug-report reference;
- cancellation/terminal reason;
- durable journal/checkpoint position.

Actor state is read or mutated only through verified lexical/critical state-loan
APIs. UI projections and connector callbacks receive copied/serialized values,
not aliases into actor state.

## Deterministic reducer and side-effect boundary

DurableDevLoopV1 separates two concepts.

**Events** are durable facts already observed, for example PushRecorded,
CiCompleted, BugReportCreated, or ApprovalGranted.

**Commands** request effects that have not happened yet, for example PushCode,
AwaitCi, Evaluate, CreateBugReport, or RequestApproval.

Every emitted command has a stable effectKey. The production actor runtime
must use that key in a durable outbox/effect journal:

1. append the accepted event/transition and newly emitted effects durably;
2. claim a pending effect with an opaque attempt-bound claim token;
3. execute the external operation with the stable effect key when the provider
   supports idempotency;
4. for commands that produce a workflow event, atomically persist the external
   receipt and append the causal event with CompleteAndDeliver/completeAndAppend;
5. for terminal side effects that produce no new reducer event (CancelWork and
   Escalate), persist completion directly;
6. if execution may have happened but the receipt is uncertain, mark the effect
   AMBIGUOUS and reconcile provider state before any retry.

Replay may regenerate the same command keys, but the effect journal prevents
already-completed effects from being executed again.

When an applied outcome event arrives, the journal also verifies that the
deterministic causal effect exists and has the expected command type. If the
effect was still pending, it is retired so it cannot execute after its outcome.
If it was already claimed or ambiguous, its retry authority is revoked while
the claim/ambiguity remains visible for receipt recording or reconciliation.
This rule is iteration-aware: CI, evaluation and approval outcomes retire the
effect for the workflow's current iteration, not an assumed first iteration.

Effect completion and causal event delivery are one durability boundary.
DurableDevLoopActorV1 rejects split CompleteEffect or direct Deliver calls for
event-producing commands. A retry after a committed transaction but lost actor
reply is idempotent; the original applied transition is returned without
growing the journal. The reference layer can also repair the legacy inverse
split where the event committed while its effect was still claimed.

This is intentionally not an "exactly once network effects" claim. The runtime
must support at-least-once delivery, deduplication, idempotency and explicit
reconciliation for ambiguous external outcomes.

## Exact-commit gating

A branch name is never test evidence.

After a push, the coordinator records one exact Git object id as
expectedCommitSha. A CI completion for any different commit is classified as
STALE and cannot advance the run, even if it is otherwise successful.

V1 accepts exact 40-hex or 64-hex object ids to remain compatible with SHA-1
repositories and future SHA-256 repositories.

For mirrored CI, the correlation layer requires the exact Git tree object id
when the expectation pins a tree. GitHubWebhookActorV1 verifies the HMAC over
the exact raw webhook body and derives repository, commit, workflow-run id,
workflow name, conclusion and report URL only from that authenticated payload.
The separate Git-tree proof must name the authenticated commit. The persistent
SqliteGitHubWebhookLedgerV1 restores run correlations after ingress restart and
permanently binds each GitHub delivery id to the signed payload digest, event
name and exact tree-proof commit/tree/receipt identity. Exact redelivery may be
routed again because the downstream workflow journal is idempotent; rebinding
the same delivery id to different signed bytes or proof identity fails closed.
Ambiguous multi-run matches route nowhere.

## Delivery deduplication and audit

Every input delivery has a stable eventId, such as a GitHub delivery id or a
runtime-generated command completion id.

The v1 reducer assigns every delivery a monotonic journal sequence and records
one of:

- APPLIED: state advanced and zero or more commands were emitted;
- DUPLICATE: a previously sealed delivery id was redelivered with the exact same immutable event;
- CONFLICT: the delivery id was reused with a different event payload;
- STALE: the event was well-formed but targeted an obsolete commit and is sealed against later reuse;
- REJECTED: the event is not legal in the current phase; the same delivery id and exact payload may be retried later if the workflow reaches a legal phase.

Duplicate, stale and rejected deliveries are no-ops for business state but stay
auditable in the journal.

A production persistence layer may compact old delivery-id indexes only after a
documented retention/replay boundary makes the entries impossible to redeliver.

## Bounded loop policy

The coding loop is never unbounded. maxIterations is part of durable run
configuration.

A failing CI/evaluation result enters WRITING_BUG_REPORT. After the structured
bug report is recorded:

- if the budget remains, iteration increments and a new RequestCode command is
  emitted with the bug report as feedback;
- if the budget is exhausted, the workflow enters FAILED and emits Escalate.

WorkflowSupervisorActorV1 and SqliteWorkflowSupervisorLedgerV1 enforce
wall-clock, model-token, CI-run, spend, ambiguity and protected-write policy.
Concurrent SQLite connections serialize admission so remaining budget cannot be
double-spent. When connected to the workflow journal, cancellation is
workflow-authoritative: the reducer reaches CANCELLED before the accounting
ledger is updated, and restart reconciles a committed workflow cancellation
back into the ledger. Terminal workflow state also blocks further admission.

WorkflowActivityProtocolV1 grants explicit capabilities by activity kind.
Succeeded is reserved for commands that must emit a causal reducer event;
Completed is reserved for terminal side effects such as CancelWork/Escalate
that intentionally produce no new workflow event.

## Proposed Oreslang surface

The exact syntax below is a design target; it is not asserted as implemented
compiler grammar yet.

The Java reference actor does **not** relax the language-level invariant that a
concrete Oreslang actor should own persistent guest data through one state
schema and verified lexical/critical state loans. Current canonical main still
contains legacy actor examples, while the single-state/state-loan work is being
reconciled on separate actor-language branches. A guest DevLoopActor must not
be advertised as executable until that canonical language/runtime support
lands; this reference layer is the durability contract it must implement.

~~~ores
define enum DevLoopMessages as
    Start struct { goal: string } 1;
    CodeChanged struct { iteration: int, patch_ref: string } 2;
    PushRecorded struct { iteration: int, commit_sha: string } 3;
    CiCompleted struct {
        commit_sha: string,
        conclusion: CiConclusion,
        report_ref: string
    } 4;
    EvaluationPassed struct { commit_sha: string } 5;
    EvaluationFailed struct {
        commit_sha: string,
        reason_ref: string
    } 6;
    BugReportCreated struct {
        iteration: int,
        commit_sha: string,
        bug_report_ref: string
    } 7;
    ApprovalGranted struct { commit_sha: string } 8;
    Cancel struct { reason: string } 9;
end

define actor DevLoopActor as
    state {
        val string run_id;
        var DevLoopPhase phase;
        var int iteration;
        val int max_iterations;
        var Option<string> expected_commit_sha;
        var Option<string> last_bug_report_ref;
    }

    private abstract receive(
        ActorMail<DevLoopMessages> mail
    ): void {
        // Decode the typed event, apply the deterministic reducer,
        // persist transition/outbox records, then dispatch effects.
        // No direct network/model/Git side effect is performed before
        // the transition is durably committed.
    }
end
~~~

## UI contract

The Java reference backend includes WorkflowRunProjectionV1, which refuses to
project reducer/store revision skew, exposes effect counts and exact workflow
revision, and surfaces approval/ambiguous-effect attention states without
granting mutation authority. WorkflowViewActorV1 publishes those immutable
projections to UI clients.

The Java bootstrap `WorkflowViewActorV1` is intentionally an
**ephemeral SHARED runtime router**, not workflow authority. Its live
`ActorRef` subscribers are runtime capabilities and are never persisted in the
workflow journal or SQLite state. The durable reducer/journal remains the sole
authority for the projection it forwards.

This is also an explicit bootstrap limitation: the current runtime has no
owner-checked dynamic capability table for a PRIVATE actor, and
`ActorEventBus` currently rejects PRIVATE subscribers. Therefore the view
actor is **not** being converted to PRIVATE by serializing actor handles,
resolving forgeable actor ids back into capabilities, or weakening the event
bus isolation rule. The follow-up runtime primitive should provide an
owner-scoped, runtime-owned capability-routing table (or isolated event-bus
delivery) while keeping `runId`, epoch, revision and latest projection in the
single private state schema. Until then the view actor remains non-authoritative
and must not acquire workflow mutation or external-effect authority.

The workflow UI consumes immutable actor projections such as:

~~~text
WorkflowRunProjection {
  runId
  phase
  iteration
  maxIterations
  expectedCommitSha
  latestArtifactRefs
  pendingApproval
  lastTransitionSequence
}
~~~

UI commands (pause, cancel, approve, retry) are authenticated messages into the
workflow/control actors. They are never direct mutations of projected state.

The actor may remain in WAITING_FOR_CI for hours or days without holding a
native thread. The durable wait is represented by persisted correlation state
and resumed only when a matching event arrives.

## Failure and supervision policy

The supervisor should distinguish:

- actor/runtime failure: restart/recover from durable journal/checkpoint;
- transient activity failure: retry the same stable effect key;
- ambiguous remote effect: reconcile provider state before retry;
- invalid/stale external event: audit but do not advance;
- retry/iteration budget exhaustion: escalate;
- explicit cancellation: enter terminal CANCELLED, abandon pending work that
  never started, leave already-claimed work visible for reconciliation, and
  reject later state-advancing events.

AI/model failures are activity failures, not reasons to replay an old model call
as if it were deterministic.

WorkflowEffectReconcilerV1 handles ambiguous provider effects conservatively:
an exact expected Git commit+tree proves a push completed; an unchanged
pre-push ref can be retried; unexpected remote movement requires human review.
Create-style APIs reconcile via a stable effect marker and require a unique
match before accepting completion.

## Current executable conformance

The branch now includes executable Java/Graal reference tests for:

- deterministic reducer transitions, duplicate/conflict/stale delivery handling,
  bounded retries and replay;
- journal/outbox claim leasing, opaque claim capabilities, causal retirement,
  receipt reconciliation and fail-closed persistent restore;
- SQLite transaction fault injection at every pre-COMMIT stage plus real
  child-process death before and after COMMIT;
- atomic effect receipt + causal event commit, including response-loss retry and
  legacy split repair;
- ActorRuntime coordinator recovery and rejection of split causal completion;
- authenticated GitHub workflow_run parsing, exact repository/commit/tree/
  workflow matching, ambiguous-correlation rejection and delivery replay rules;
- capability-checked coding/Git/CI/evaluation/bug-report/approval/cancel/
  escalation activity actors with eventful versus terminal completion semantics;
- durable supervisor budget accounting, concurrent budget serialization,
  workflow-authoritative cancellation and restart reconciliation;
- immutable workflow UI projection and actor subscription surfaces;
- typed workflow graph validation, canonical graph fingerprints, persisted
  traversal history, bounded-cycle enforcement and cross-connection advances.

The generic typed workflow runtime deliberately starts with one active control
token. Parallel fan-out/join and compensation are later graph primitives, not
implicit behavior in v1.

## Next implementation slices

1. Integrate the durable coordinator with the canonical Oreslang single-state
   actor/state-loan surface once that language work lands; do not regress to
   ordinary actor instance fields.
2. Add PostgreSQL persistence with the same deterministic replay, atomic
   receipt+event, leasing, delivery-identity and crash/reconciliation contracts
   as the SQLite reference.
3. Add concrete sandboxed coding/Git/CI/model/provider adapters that consume
   WorkflowActivityProtocolV1 claims, preserve worker ownership, emit durable
   receipts and implement provider-specific reconciliation.
4. Add actual-usage settlement for model tokens/spend in addition to current
   pre-execution durable reservation/admission accounting.
5. Add parallel fan-out/join, durable timers/waits and compensation as explicit
   typed graph primitives. V1 intentionally has one active control token.
6. Add Java/LLVM differential trace fixtures for reducer, journal, webhook
   correlation and typed workflow execution.
7. Extend the independent TLA+/TLC durable-workflow model with refinement
   traces for concrete journal operations, multi-effect FIFO interactions and
   process-crash recovery; the v1 claim/reconciliation model is already wired
   into the pinned formal CI gate.
8. Add an owner-scoped runtime capability-routing primitive (or isolated
   ActorEventBus delivery) so the ephemeral UI router can become PRIVATE
   without persisting or reconstructing live ActorRef capabilities.
9. Restore exact-tree GitHub Actions evidence when a private validation
   destination is explicitly approved by the repository's protected CI policy.
