# Actor physical dispatch failures: receipt-safe fail-seal audit (2026-10-19)

**Status: DRAFT.** This patch hardens Java/Graal SHARED actor runtime scheduling. It does not prove guest task-code compilation, PRIVATE/UNTRUSTED physical isolation, signed native code materialization, or the full atomic hot-upgrade transaction.

## Finding and production change

`ActorRuntime.ActorCell.schedule()` previously caught only `RejectedExecutionException` after claiming the actor's `scheduledDispatchToken` and `scheduled` bit. A dispatcher/allocator could also raise another `RuntimeException` or an `Error`, including an error after an executor accepted the dispatch runnable. An already-accepted physical message could then retain a pending exact-message receipt while no carrier executes it, preventing safe actor upgrade and cleanup.

The runtime now handles **RuntimeException or Error** submission failures without replaying the dispatch ticket:

- Under the actor's lifecycle lock, permanently seal ordinary admission and fence new carrier turns; clear the scheduled ticket only if this caller still owns it and no carrier entered.
- Request fail-stop actor teardown, which must fail unresolved exact physical mail receipts. A late executor runnable sees a stale ticket/fenced actor and cannot resume guest execution.
- If teardown itself throws, preserve the original submission failure and attach the cleanup error as suppressed. The actor remains **sealed, not falsely marked finalized**, for supervisor retry. An actual process-level OOME can still be unrecoverable; this is not an OS-level fault-containment promise.
- Preserve the prior special case that ignores an expected RejectedExecutionException after the whole runtime is already closed.

### Runtime tests committed; real JUnit execution pending

`ActorRuntimePhysicalMailReceiptTest` adds two host-only reflection fault tests for unexpected dispatcher RuntimeException and Error after the runnable is captured; both assert actor termination, no guest execution and a permanently inert stale runnable.

`ActorRuntimeScheduleFailureSealTest` injects BOTH a dispatcher failure and an OOME in the receipt-ledger teardown snapshot. It checks primary/suppressed exception identities, persisted actor ingress seal, unacknowledged retained physical receipt, and successful terminal receipt failure after restoring the ledger and retrying supervisor stop. These tests are **committed but have not executed in full Maven/JDK25/GraalVM CI**.

## Offline runnable evidence

Local Java 21 `javac --release 21 -Xlint:all -Werror` compiled `ActorScheduleFailureProbe.java`, an isolated **source-shaped protocol model**, not the full ActorRuntime source. The model executed **94,000 PASS assertions**:

- 5,000 alternating dispatch RuntimeException/Error cases: original failure propagation, ticket revocation, actor fencing, inert late runnable;
- 5,000 dispatcher failures plus injected one-time teardown OOME: actor stays sealed, preserves suppressed failure, and a later supervisor stop completes;
- 2,000 already-closed admission cases: no submission occurs.

This provides protocol-level evidence, **not** production receipt/actor execution or exact-head integration CI evidence.

## CI/provenance hold

The source owner's Actions jobs continue failing with **zero executed steps**; that condition alone does not prove an actual budget exhaustion or compiler/test failure. Alternate organizations have previously run *source-free* CI, but copying any Oreslang private source tree is separately blocked by open `oreslang-platform-conformance` security incidents #22 and #77, and CI source/destination approval #69, plus `oreslang-source.java` authorization issue #962. No private source was exported to any cross-org carrier during this audit.

The PR must remain **DRAFT**. Next required validation: approved exact-head JDK25/Maven/GraalVM JUnit, asynchronous accepted-message/receipt failure matrices, atomic code/scheduler/state/route publication, and confirmed physical retirement/isolation for native and untrusted targets.
