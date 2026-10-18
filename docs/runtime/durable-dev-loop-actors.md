# Durable actor development loops

Status: **v1 runtime kernel implemented; actor/tool adapters are follow-up work.**

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

1. append the accepted event/transition durably;
2. append or recover the pending effect by effectKey;
3. execute the external operation with that idempotency key when the provider
   supports one;
4. persist the result/receipt before publishing the corresponding completion
   event back to the actor.

Replay may regenerate the same command keys, but the effect journal prevents
already-completed effects from being executed again.

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

For mirrored CI, a future CIActor must also record proof that the tested Git
tree is byte-for-byte the intended source tree before accepting the result.

## Delivery deduplication and audit

Every input delivery has a stable eventId, such as a GitHub delivery id or a
runtime-generated command completion id.

The v1 reducer assigns every delivery a monotonic journal sequence and records
one of:

- APPLIED: state advanced and zero or more commands were emitted;
- DUPLICATE: the delivery id was already seen;
- STALE: the event was well-formed but targeted an obsolete commit;
- REJECTED: the event is not legal in the current phase.

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

Additional limits should be enforced above the reducer for wall-clock time,
model/token spend, CI spend, filesystem/network capabilities and protected Git
operations.

## Proposed Oreslang surface

The exact syntax below is a design target; it is not asserted as implemented
compiler grammar yet.

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
- explicit cancellation: enter terminal CANCELLED, revoke pending work where
  possible and reject later state-advancing events.

AI/model failures are activity failures, not reasons to replay an old model call
as if it were deterministic.

## Current executable conformance

DurableDevLoopV1Test covers:

- happy-path code -> push -> exact CI -> evaluation -> approval;
- stale commit CI rejection;
- duplicate delivery-id no-op behavior;
- CI failure -> bug report -> next coding iteration;
- bounded iteration exhaustion and escalation;
- evaluation failure using existing test evidence;
- deterministic journal replay and stable regenerated effect keys;
- cancellation before start;
- malformed Git object-id rejection.

## Next implementation slices

1. Add a durable WorkflowJournal / outbox interface with an in-memory reference
   adapter and crash-window tests.
2. Wrap DurableDevLoopV1 in a real Oreslang DevLoopActor using the single actor
   state schema and verified state-loan API.
3. Add authenticated GitHub webhook ingress with delivery-id dedupe and
   repository/run/commit correlation.
4. Add Git/CI effect adapters that bind receipts to exact commit/tree identity.
5. Add AI coding/evaluation activities with explicit tool capabilities,
   sandboxing and spend/iteration policy.
6. Add WorkflowRunProjection and subscription messages for a UI node/timeline.
7. Add Java/LLVM conformance fixtures for the same journal/state-machine traces.
8. Add crash/fault-injection tests around every transition/effect boundary.
