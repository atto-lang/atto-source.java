# Mandatory nonblocking core I/O — 2026-10-15

Core I/O is **strict by default and cannot be disabled** in these hardening
branches. This supersedes the October 13 opt-in proposal. There is no
`LEGACY_BLOCKING` policy. `--core-io=native-async-only` only restates the
invariant; `legacy-blocking`, `off`, unknown and duplicate modes are rejected.
No flag is needed for ordinary CLI or embedded contexts.

## Java/Graal changes

- The type checker rejects legacy fs, HTTP, TCP and stdio calls and first-class
  aliases before execution, independently of permissions and dead-code removal.
  An `_async` suffix does not admit an implementation backed by blocking calls.
- Runtime guards remain mandatory for precompiled/embedding boundaries. The
  retired `AsyncNativeIo` has no executor and cannot execute submitted work.
  Context startup creates neither the old worker service nor Java HTTP service.
- The Java filesystem, buffered socket and HTTP-client implementations were
  removed from `NativeIo`. Closed signatures remain for evaluator diagnostics;
  environment reads retain their capability checks.
- `std/net.listen_loopback` now returns `Future<NioTcpListener>`. Its socket and
  selector initialization occur on its owned reactor, never a guest carrier.
  The synchronous factory/builtin is gone. Loopback uses literal address bytes,
  avoiding DNS, and listener port access reads a cached value without a syscall.
- Open Futures count against context listener limits. Open publication and
  context close are serialized; teardown revokes pending opens and cleans up
  listeners that finish after context closure. Already-terminated listeners
  cannot leave stale quota entries. Integer narrowing cannot wrap ports/limits.
- `std/testing` returns native Oreslang `TestEvent` records in `RunSummary`.
  Reporting no longer performs synchronous stdout writes. Filtering, skip and
  fail-fast behavior are exercised by the real Oreslang examples, without a Java
  testing-framework implementation. Fail-fast moves its owning event list only
  after leaving the loop.

The HTTP regression executes actual Oreslang framing/response policy over
nonblocking TCP for fragmented requests, pipelines and invalid framing. It
uses no synchronous guest stdout handshake. All packaged Oreslang stdlib
modules must independently pass the mandatory compiler admission policy.

## LLVM changes

Unsupported native guest I/O remains rejected without emitting IR, with no
opt-in required. The CLI cannot select a blocking compatibility policy. Tests
cover both the default and redundant strict assertion, every unsupported call
under both, and downgrade/duplicate rejection.

The newly merged Linux TCP primitive uses nonblocking sockets and
`MSG_DONTWAIT`; writes suppress SIGPIPE. Its accept boundary now checks that
the **listener** remains nonblocking before `accept4`: flags passed to
`accept4` only govern the newly accepted socket. A regression deliberately
clears the listener flag, expects immediate EINVAL, then restores it.

## Verification and limits

Local Temurin JDK 25: **39 focused tests passed**, including real Oreslang HTTP,
all packaged stdlib admissions, native testing records, strict defaults and
worker refusal, asynchronous bind failure, listener quotas, 30 open/close races,
port narrowing and reactor operation/cancellation races. LLVM 20 Release:
**23 focused CTests passed**, plus the function-effect bitset check.

A broad Java migration inventory on the intermediate cutover ran 2,186 tests:
168 failures, 285 errors, one skip. Most still expect synchronous stdio/legacy
I/O or Java interop and must be migrated to the new contract. This is a breaking
API cutover, **not a green full-suite or merge approval**. The focused green
slice does not replace required origin checks. Do not restore compatibility
modes to make these old expectations pass.

The private Actions fallback is authorized. Source-org billing failures were
confirmed. Two previously used private destinations were also blocked. The
private `led-dynamo/leddy-e2e` repository passed runner-admission probe
[38769097500](https://github.com/led-dynamo/leddy-e2e/actions/runs/38769097500).
Exact source snapshots, hashes and executed validation receipts are retained
under the org-root `audit-reports/2026-10-15-mandatory-async` directory.

Remaining implementation work is explicit: genuine async filesystem and stdio
backends are unsupported and **reject**, rather than using workers. The current
Graal TCP kernel adapter still uses Java NIO; protocol and test-library policy
are Oreslang. Native C/JNI adapter replacement, general connect/DNS/TLS,
actor-owned transferable socket handles and full LLVM guest-I/O continuation
lowering remain incomplete. Idle reactor readiness waits and compiler/source
loading are distinct from guest blocking-I/O fallbacks. No Native Image/AOT or
production-completeness claim is made by this slice.

The all-backend source corpus additionally exposed a stdio namespace admission
gap: unknown direct members such as stdio.read_line previously compiled as
unknown calls. The mandatory type gate now rejects these and namespace aliases
before execution, while supported non-I/O source and packaged stdlib still pass.
