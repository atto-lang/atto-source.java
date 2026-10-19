# Trusted IPC transport session lease — source-level host substrate

Status: **DRAFT / host substrate**, not an authenticated peer transport, actual socket adapter, Graal guest execution, or proof of native LLVM parity.

## Source seam

- `IpcTransportSessionLease` is a package-local, trusted-host-only object for one immutable authenticated transport epoch (domain, sender/destination, incarnations, session, route and capability generation) and a monotonic physical `generation`, with bounded active writes.
- The synchronous `FrameWriter` is **bound once at lease construction** with the trusted epoch/generation, closer and cleanup scheduler; callers cannot substitute a different physical writer per frame. `writeFrame(generation, envelope)` serializes **session admission + `IpcReceiptCompletionBridge.commitDispatch`** against close intent before any guest-external physical write, and an active write keeps that bound physical session pinned.
- The supplied host writer MUST perform and finish the physical attempted write before returning. No async fire-and-forget, guest callback, mixed-generation socket handoff, foreign session, unverified DNS/proxy hop or caller-provided authorization.
- Once dispatch commits, an unsuccessful or uncertain socket write **cannot** become `REJECTED_BEFORE_REMOTE`. The broker must close/drain or quarantine and only mark `OUTCOME_UNKNOWN` with the local trusted transport authority.
- `requestClose` admits no further writers. Physical close and trusted receipt retirement run only after all pinned writers drain.
- `CleanupScheduler` MUST be a bounded, non-caller-runs, host-owned worker queue. The runtime now detects same-thread caller-runs execution and quarantines before physical close; rejected scheduling also quarantines without falsely completing futures. The one `Runnable::run` use in the self-test is an intentional **negative** case proving that fail-closed behavior.
- Cleanup closure is generation/attempt-fenced and single-flight. Duplicate or stale scheduled cleanup callbacks do not double-close a descriptor or steal a later retry.
- If physical close fails, the session is `QUARANTINED`: no reuse, no new sends, and no fabricated physical completion. `retryQuarantinedClose` is an explicit host-supervisor action; no automatic spinning. If physical close was confirmed but receipt retirement failed, retry skips the physical close and resumes receipt retirement.
- Verified remote receipts still require independently authenticated peer, frame, route, incarnation, capability and digest authority. `IpcTransportSessionLease` does not authenticate any of those.

## Exact local host checks

`scripts/check-ipc-receipt-parity.sh` now compiles using `javac -Xlint:all -Werror`:
- `IpcReceiptCorrelation.java`
- `IpcReceiptCompletionBridge.java`
- `IpcTransportSessionLease.java`
- their three host self-test classes

The Maven/JUnit `IpcReceiptParityTest` includes `trustedPhysicalSessionWriteAndCloseDrain`.

New host test vectors: successful verified receipt, denied stale generation, local never-sent denial, failed socket write/uncertain terminal, failed physical close quarantine, explicit retry, deferred close task and denied worker capacity, duplicate/stale cleanup task, 512 pinned close-vs-write cases, 512 close-vs-admit races.

Local independent Java 21 compilation checked a matching transport-epoch lease algorithm against a minimal host bridge stub under `javac -Xlint:all -Werror` with **6,168 passing assertions**, including 1,024 real Java thread races. This is **NOT an exact source Java compilation**: the actual source-repo host suite has been committed but source-org Actions provide no executed steps.

## Release gates still open

1. Bind the lease constructor and frame writer to a genuine authenticated two-process Java IPC connection, with a physical session/descriptor epoch managed by the supervisor. No direct UNTRUSTED NETWORK capability.
2. Require the real adapter's *every* write path to enter `writeFrame`; validate full payload/frame digest and credentials at the trusted boundary. No direct writer bypass, no asynchronous write that returns before physical completion.
3. Prove bounded close/cancel/timeout escalation for blocked writers and actual OS closure or quarantine, including descriptor reuse/ABA, actor retirement and hot upgrades.
4. Execute exact-current-head source-local JDK21 strict host suite, JDK25 Maven tests, GraalVM isolated guest and Native Image where applicable. Add LLVM-native authenticated adapter parity.
5. Independently authorize any cross-organization private-source transport under current security incidents conformance #22/#77 and Java private-CI PR #1217. A funded runner, a PRIVATE repository and matching blob SHAs alone are **not sufficient approval**.

No PR is production-ready until these gates are independently reviewed and executed.


## Follow-up close scheduler and epoch hardening

- Physical cleanup is submitted to the injected bounded `CleanupScheduler`, never performed by `requestClose` under the session gate; the host must supply a genuine non-caller-runs worker. A rejected queue reservation quarantines the physical session.
- A monotonic per-session cleanup attempt fences stale, duplicated and race-delivered cleanup tasks. Only one callback may execute, and retry after a failed cleanup cannot be hijacked by a stale task.
- A successful explicit quarantine retry clears the prior failure diagnostic only after both physical-close confirmation and trusted-local receipt retirement.
- The trusted local EOF retirement keys off a full, attested transport epoch, not textual session ID. A recycled name cannot terminate a newer route/grant/incarnation's pending messages.
- The `Runnable::run` injected executor appearing in deterministic JDK tests is a host-only test fixture; production adapters must supply a bounded, nonparking, never-caller-runs scheduler with audited failure reporting.


## Exact production-lease execution (Oct 19 follow-up)

The exact current GitHub `IpcTransportSessionLease.java` blob `46132a42779b54baed6f7de0e561df734f72ead3` was reconstructed locally and its Git blob SHA recomputed to the same value before execution. With a deliberately independent minimal bridge/receipt harness, **the production lease bytes** compiled under OpenJDK 21 `javac -Xlint:all -Werror` and passed **12,050 checks**, including **2,000 real Java close-vs-send thread races**. The exact-class run covered bound-writer ordering, full epoch mismatch, stale physical generation, caller-runs cleanup quarantine, scheduler rejection, uncertain write failure, failed physical close plus explicit retry, stale queued cleanup attempts and pin/drain before close.

This is stronger than a synthetic reimplementation but still narrower than source-repository integration: the bridge/correlation harness was independent, so it does **not** certify the exact `IpcReceiptCompletionBridge` / `IpcReceiptCorrelation`, Maven, Graal guest, Native Image, LLVM, or authenticated socket adapter. Source-org Actions remain required.

A separate private test org has current successful source-free Actions capacity (for example source-free IPC epoch run `39313810696`), but private Java export remains disallowed while conformance incidents #22/#77 are open and private-CI dispatcher PR #1217 has no independent exact-snapshot/destination approval.
