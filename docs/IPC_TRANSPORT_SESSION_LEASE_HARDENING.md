# Trusted IPC transport session lease — source-level host substrate

Status: **DRAFT / host substrate**, not an authenticated peer transport, actual socket adapter, Graal guest execution, or proof of native LLVM parity.

## Source seam

- `IpcTransportSessionLease` is a package-local, trusted-host-only object for one immutable `session` and monotonic `generation`, with bounded active writes.
- `writeFrame(generation, envelope, synchronousHostWriter)` serializes **session admission + `IpcReceiptCompletionBridge.commitDispatch`** against close intent, before any guest-external physical write; an active write keeps the physical session pinned.
- The supplied host writer MUST perform and finish the physical attempted write before returning. No async fire-and-forget, guest callback, mixed-generation socket handoff, foreign session, unverified DNS/proxy hop or caller-provided authorization.
- Once dispatch commits, an unsuccessful or uncertain socket write **cannot** become `REJECTED_BEFORE_REMOTE`. The broker must close/drain or quarantine and only mark `OUTCOME_UNKNOWN` with the local trusted transport authority.
- `requestClose` admits no further writers. Physical close and trusted receipt retirement run only after all pinned writers drain.
- `CleanupScheduler` MUST be a bounded, non-caller-runs, host-owned worker queue. The host test's `Runnable::run` mode is only a deterministic JUnit fixture, not an acceptable actor carrier scheduler. Rejected scheduling quarantines the session without falsely completing futures.
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

Local independent Java 21 compilation checked an equivalent lease algorithm against a minimal host bridge stub under `javac -Xlint:all -Werror` with **6,168 passing assertions**, including 1,024 real Java thread races. This is **NOT an exact source Java compilation**: the actual source-repo host suite has been committed but source-org Actions provide no executed steps.

## Release gates still open

1. Bind the lease constructor and frame writer to a genuine authenticated two-process Java IPC connection, with a physical session/descriptor epoch managed by the supervisor. No direct UNTRUSTED NETWORK capability.
2. Require the real adapter's *every* write path to enter `writeFrame`; validate full payload/frame digest and credentials at the trusted boundary. No direct writer bypass, no asynchronous write that returns before physical completion.
3. Prove bounded close/cancel/timeout escalation for blocked writers and actual OS closure or quarantine, including descriptor reuse/ABA, actor retirement and hot upgrades.
4. Execute exact-current-head source-local JDK21 strict host suite, JDK25 Maven tests, GraalVM isolated guest and Native Image where applicable. Add LLVM-native authenticated adapter parity.
5. Independently authorize any cross-organization private-source transport under current security incidents conformance #22/#77 and Java private-CI PR #1217. A funded runner, a PRIVATE repository and matching blob SHAs alone are **not sufficient approval**.

No PR is production-ready until these gates are independently reviewed and executed.
