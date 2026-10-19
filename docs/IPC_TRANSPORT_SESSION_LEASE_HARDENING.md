# Trusted IPC transport session lease — source-level host substrate

Status: **DRAFT / trusted host substrate plus localhost HMAC-framed socket adapter**. It is not complete deployment peer identity, Graal guest execution, or proof of native LLVM parity.

## Source seam

- `IpcTransportSessionLease` is a package-local, trusted-host-only object for one immutable authenticated transport epoch (domain, sender/destination, incarnations, session, route and capability generation) and a monotonic physical `generation`, with bounded active writes.
- The synchronous `FrameWriter` is **bound once at lease construction** with the trusted epoch/generation, closer and cleanup scheduler; callers cannot substitute a different physical writer per frame. `writeFrame(generation, envelope, preparedFrame)` accepts only already-prepared frame bytes as data, serializes **session admission + `IpcReceiptCompletionBridge.commitDispatch`** against close intent before any guest-external physical write, and keeps that bound physical session pinned until the write returns.
- The supplied host writer MUST perform and finish the physical attempted write before returning. No async fire-and-forget, guest callback, mixed-generation socket handoff, foreign session, unverified DNS/proxy hop or caller-provided authorization.
- Once dispatch commits, an unsuccessful or uncertain socket write **cannot** become `REJECTED_BEFORE_REMOTE`. The broker must close/drain or quarantine and only mark `OUTCOME_UNKNOWN` with the local trusted transport authority.
- `requestClose` admits no further writers. If a physical write is pinned, the socket abort is submitted to the bounded non-caller-runs cleanup domain instead of running `Socket.close()` on the requester. `requestCloseAndAwait` bounds supervisor waiting and quarantines unresolved generations; timeout is never evidence of successful close.
- `CleanupScheduler` MUST be a bounded, non-caller-runs, host-owned worker queue. The runtime now detects same-thread caller-runs execution and quarantines before physical close; rejected scheduling also quarantines without falsely completing futures. The one `Runnable::run` use in the self-test is an intentional **negative** case proving that fail-closed behavior.
- Cleanup closure is generation/attempt-fenced and single-flight. Duplicate or stale scheduled cleanup callbacks do not double-close a descriptor or steal a later retry.
- If physical close fails, the session is `QUARANTINED`: no reuse, no new sends, and no fabricated physical completion. `retryQuarantinedClose` is an explicit host-supervisor action; no automatic spinning. If physical close was confirmed but receipt retirement failed, retry skips the physical close and resumes receipt retirement.
- Verified remote receipts still require independently authenticated peer, frame, route, incarnation, capability and digest authority. `IpcTransportSessionLease` does not authenticate any of those.

## Exact local host checks

`scripts/check-ipc-receipt-parity.sh` now compiles using `javac -Xlint:all -Werror`:
- `IpcReceiptCorrelation.java`
- `IpcReceiptCompletionBridge.java`
- `IpcTransportSessionLease.java`
- `IpcAuthenticatedSocketTransport.java`
- their four host self-test classes

The Maven/JUnit `IpcReceiptParityTest` includes both the physical-session lease test and the authenticated localhost socket test.

New host test vectors: successful verified receipt, denied stale generation, local never-sent denial, failed socket write/uncertain terminal, failed physical close quarantine, explicit retry, deferred close task and denied worker capacity, duplicate/stale cleanup task, 512 pinned close-vs-write cases, 512 close-vs-admit races.

Local independent Java 21 compilation checked a matching transport-epoch lease algorithm against a minimal host bridge stub under `javac -Xlint:all -Werror` with **6,168 passing assertions**, including 1,024 real Java thread races. This is **NOT an exact source Java compilation**: the actual source-repo host suite has been committed but source-org Actions provide no executed steps.

## Release gates still open

1. Bind the localhost HMAC-framed adapter to the production broker's independently authenticated endpoint/key-establishment policy (TLS/Noise/mTLS or equivalent), monotonic physical-generation allocator and descriptor ownership. No direct UNTRUSTED NETWORK capability.
2. Prove every production outbound path uses this adapter/lease with no direct socket writer bypass; incoming DATA still needs the real actor/proxy delivery integration and capability checks.
3. Extend bounded close/cancel/timeout escalation through actor retirement, supervisor teardown, hot upgrades and real OS descriptor reuse/ABA. The current host tests cover blocked Java Socket reads/writes, scheduler failure, timeout quarantine and explicit recovery.
4. Execute exact-current-head source-local JDK21 strict host suite, JDK25 Maven tests, GraalVM isolated guest and Native Image where applicable. Add LLVM-native authenticated adapter parity and two-process fault injection.
5. Independently authorize any cross-organization private-source transport under current security incidents conformance #22/#77 and Java private-CI PR #1217. A funded runner, a PRIVATE repository and matching blob SHAs alone are **not sufficient approval**.

No PR is production-ready until these gates are independently reviewed and executed.


## Follow-up close scheduler and epoch hardening

- Physical cleanup is submitted to the injected bounded `CleanupScheduler`, never performed by `requestClose` under the session gate; the host must supply a genuine non-caller-runs worker. A rejected queue reservation quarantines the physical session.
- A monotonic per-session cleanup attempt fences stale, duplicated and race-delivered cleanup tasks. Only one callback may execute, and retry after a failed cleanup cannot be hijacked by a stale task.
- A successful explicit quarantine retry clears the prior failure diagnostic only after both physical-close confirmation and trusted-local receipt retirement.
- The trusted local EOF retirement keys off a full, attested transport epoch, not textual session ID. A recycled name cannot terminate a newer route/grant/incarnation's pending messages.
- The `Runnable::run` injected executor appearing in deterministic JDK tests is a host-only test fixture; production adapters must supply a bounded, nonparking, never-caller-runs scheduler with audited failure reporting.


## Historical exact production-lease execution (superseded blob)

An earlier `IpcTransportSessionLease.java` blob `46132a42779b54baed6f7de0e561df734f72ead3` was reconstructed locally and its Git blob SHA recomputed to the same value before execution. With a deliberately independent minimal bridge/receipt harness, **the production lease bytes** compiled under OpenJDK 21 `javac -Xlint:all -Werror` and passed **12,050 checks**, including **2,000 real Java close-vs-send thread races**. The exact-class run covered bound-writer ordering, full epoch mismatch, stale physical generation, caller-runs cleanup quarantine, scheduler rejection, uncertain write failure, failed physical close plus explicit retry, stale queued cleanup attempts and pin/drain before close.

That result is historical only. The lease has since changed to add prepared-frame data, bounded close waiting and timeout quarantine; it **must not** be cited as execution evidence for the current blob. It also never certified the exact bridge/correlation, Maven, Graal guest, Native Image, LLVM, or authenticated socket adapter. Source-org Actions remain required.

A separate private test org has current successful source-free Actions capacity (for example source-free IPC epoch run `39313810696`), but private Java export remains disallowed while conformance incidents #22/#77 are open and private-CI dispatcher PR #1217 has no independent exact-snapshot/destination approval.


## Authenticated localhost socket adapter follow-up

The branch now also contains package-private `IpcAuthenticatedSocketTransport`,
which is the first concrete Java socket integration of the host substrate.

- Guest actors cannot construct it or obtain the `Socket`, HMAC key,
  `IpcTransportSessionLease`, frame writer, cleanup scheduler or physical
  close authority.
- DATA and remote receipt frames use bounded binary framing and HMAC-SHA-256.
  The MAC covers protocol type, immutable logical transport epoch, individual
  message identity/digest **and the physical connection generation**. A valid
  MAC captured from generation N is rejected by generation N+1.
- The adapter verifies payload SHA-256 before admission and on DATA decode.
  Remote receipts admit only `REMOTE_ACCEPTED`, `HANDLER_COMMITTED` and
  `HANDLER_FAILED`; even a peer that knows the MAC key cannot assert
  `OUTCOME_UNKNOWN` or `REJECTED_BEFORE_REMOTE`.
- Every physical DATA write enters `IpcTransportSessionLease.writeFrame`,
  which commits `DISPATCH_COMMITTED` before the first stream write. A lease
  rejection before the writer callback completes locally as
  `REJECTED_BEFORE_REMOTE`. Writer failure after commitment is never replay
  authority and eventually retires as `OUTCOME_UNKNOWN`.
- Authentication, framing, stale-generation, EOF and receipt-state errors
  fail-close the physical socket instead of continuing on a possibly
  desynchronized stream.
- `requestClose` first fences new lease admission. For an active physical
  writer it submits the Socket abort to the bounded cleanup worker instead of
  closing on the requester. `requestCloseAndAwait` provides a bounded host
  supervisor wait; an undrained writer becomes `QUARANTINED`, never
  successfully closed by inference. Abort scheduling failure can be retried
  explicitly with `retryQuarantinedAbort`; after the pin drains, receipt
  retirement remains a separate explicit `retryQuarantinedClose`.
- The localhost self-test exercises kernel socket I/O, successful
  DATA→ACK→HANDLER_COMMITTED, stale logical epoch, close-before-send,
  authenticated local-verdict forgery, valid-MAC old physical generation,
  MAC tampering, cleanup-scheduler quarantine/retry, blocked receipt reads and
  a pressured 8 MiB synchronous write interrupted by close.

### Still not production peer authentication

HMAC possession is a concrete authenticated framing mechanism, not a complete
deployment identity system. The embedding supervisor/broker must establish
and rotate the 32+ byte key over a separately authenticated channel (for
example TLS/Noise/mTLS or a pre-provisioned host trust root), mint a monotonic
non-reused physical generation, bind endpoint policy, and keep key material
unavailable to guest actors. This PR does not implement DNS authorization,
certificate validation, remote key exchange, credential storage or LLVM peer
parity.

The real localhost socket self-test is checked into the exact Java branch and
wired into `scripts/check-ipc-receipt-parity.sh` and `IpcReceiptParityTest`.
Source-org Actions still need to actually allocate a runner before those
source files can be claimed as executed.


## Replay, pre-authentication memory and key-lifetime hardening

The authenticated framing layer now additionally treats transport ordering and
pre-MAC resource use as security invariants:

- \`messageSequence\` **and** \`frameSequence\` are independently strictly
  increasing for one authenticated outbound stream. Advancing the logical
  message number does not authorize frame-sequence rollback.
- Authenticated DATA decode requires a per-connection
  \`InboundReplayGate\`. The gate is constant-memory and rejects an exact
  valid-MAC replay, message rollback, or frame rollback after full
  MAC/digest/epoch/generation verification. Because this transport is a TCP
  byte stream, authenticated decreasing sequence is stale/replayed state, not
  benign network reordering.
- DATA decoding is safe-by-default: callers cannot decode a frame without
  supplying expected logical epoch, physical generation and replay gate.
- Receipt reads use a **1024-byte pre-authentication frame cap** instead of the
  DATA-size bound. A hostile length prefix is rejected before allocating or
  reading the advertised body. DATA remains bounded to the 8 MiB payload limit
  plus fixed framing headroom.
- The adapter owns a private clone of broker key material. Its clone is
  zero-filled by the authoritative physical-close callback only after admitted
  physical writers have drained. The broker's input array is never modified.
  A cleanup-scheduler rejection leaves the session quarantined and key clone
  intact until explicit supervisor retry performs authoritative close; the
  generation remains unusable throughout quarantine.
- Source tests cover exact DATA replay, independent frame rollback, monotonic
  next-frame acceptance, oversized unauthenticated receipt length, and key
  destruction after retirement.

The source-free funded canary independently exercises the corresponding
properties without copying Oreslang source. Its current run is intentionally
evidence about hosted JDK/socket behavior and the abstract security contract,
not source-head certification.


### Managed-runtime secret-erasure caveat

Zero-filling the adapter-owned byte array is a best-effort lifetime reduction,
not proof that every key copy has been physically erased from JVM memory.
SecretKeySpec, JCE provider internals, compiler temporaries, crash dumps or
heap snapshots may contain transient copies outside this object's control.
Production key management must therefore rely on short-lived per-connection
derived keys, process/isolate boundaries and platform secret-handling policy;
the source test only proves that the adapter's retained clone is zero-filled
after authoritative retirement.


## Current-head adapter hardening (latest audit)

At the latest audit, the current source blobs are:
- `IpcTransportSessionLease.java`: `6d02bc924c5f1345ee314af9634474c4f34f777b`
- `IpcAuthenticatedSocketTransport.java`: `b087fd5d4050d58531f8f486a6f8acadb17ae50d`
- authenticated socket self-test: `f6a60d766532ef0f7cafb8945d6538b706482e20`

The socket adapter now:
- binds its physical writer at lease construction and passes only prebuilt authenticated frame bytes per send;
- finishes deterministic frame encoding before receipt admission;
- serializes correlation admission + dispatch commitment + TCP publication to prevent accepted frame reordering;
- fixes TCP physical publication concurrency at exactly one while allowing multiple outstanding receipt lifetimes;
- uses protocol **version 2** with explicit remote receipt wire codes instead of Java enum ordinals;
- rejects prior-version frames even when they carry a valid MAC;
- submits active-write socket abort to the bounded cleanup domain, provides bounded close waiting, quarantines deadline misses, and supports separate explicit abort and close-retirement recovery;
- validates close deadlines before causing any close side effect;
- destroys a cloned MAC key on constructor stream-acquisition failure and on authoritative final retirement.

The current source self-test includes real localhost kernel I/O, pressured 8 MiB blocked writers, off-carrier abort, cleanup queue rejection, explicit active-abort recovery, deliberately withheld abort/deadline quarantine, caller-runs denial, prior-version valid-MAC rejection, and pre-auth receipt allocation limits.

**Current-head execution remains unverified in source CI** until a source-org runner actually records steps. Static/source inspection is not a substitute for `javac`, Maven, GraalVM or LLVM execution.
