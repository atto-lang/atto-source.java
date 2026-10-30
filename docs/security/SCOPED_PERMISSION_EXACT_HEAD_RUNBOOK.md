# Java permission security: exact-head execution runbook

Status: **RUNNER EXECUTION PENDING**. This runbook is not a CI pass certificate.

## Chain under test

Stacked draft PRs:
- #2269: adversarial isolate capability ceiling
- #2277: actor-local immutable restriction chains and native-I/O authority
- #2286: wildcard/IPv4/IPv6 network validation and exact resource identity
- #2287 listener-port-zero PR: outbound network authorization denies port 0; explicit HTTP listener authorization can request OS-assigned port 0
- revocable-delegation follow-on draft: issuer-only, monotonic child-scope revocation; all subsequent actor/native-I/O resource authorization must deny after revocation (not preemptive syscall cancellation)
- queued I/O/listener-hardening follow-on draft: a native worker rechecks the captured delegation before running queued work; HTTP listener startup rejects both actor turns and actor-owned I/O workers (not a linearizable OS-operation cutoff)
- physical-resource-loan follow-on draft: new native loans and resource registrations reject already-revoked actor/native-worker scopes; shutdown quarantine of handles created by in-flight work remains intact (still not a complete syscall fence)
- physical drain receipt follow-on: each admitted native operation, registered actor-owned handle and active I/O loan acquires a bounded ancestor-counted lease; issuer revocation seals subsequent admission and `tryPermissionDrainReceipt()` polls without blocking. No proof of external OS cancellation is claimed.
- native registration ownership/retirement gate: an actor-owned registered socket also counts against `ActorRuntime` physical actor-operation retirement, independently of active socket read/write loans; failed TCP connect closes before unregister, and context shutdown retries actor finalization once successful native physical cleanup has occurred. Corrupt scoped lease retirement receipts also mark native-I/O completion incomplete, withholding actor-DONE evidence.

**Physical receipt admission and retirement:** `ActorPermissionScope.acquirePhysicalLease()` takes bounded root-to-leaf receipts under per-scope monitors; revocation seals subsequent admission without waiting. A child permission receipt may be issued only after that child and all descendants release previously admitted worker, active socket-loan and context-owned registration receipts. `AsyncNativeIo.Operation` releases after Future callback delivery and actor retirement notifications (or fails closed on incomplete receipts); discarded queued operations release only after terminal notification. `IoLoan` releases after the last native physical loan, while `register/unregister` and successful shutdown closure release registered resource receipts. No callbacks are invoked while holding the scope monitors. A missing or damaged native retirement receipt must leave the scope blocked, not produce an optimistic quiescence proof. If an in-flight operation registers a late handle **after context shutdown has sealed new admissions**, the runtime additionally marks that delegation and all ancestors' drain receipts as **quarantined indefinitely** until a separately reviewed reconciliation mechanism exists; post-shutdown close alone is not accepted as an ownership proof for an uncounted handle.

**Evidence caveat:** the resulting `PermissionDrainReceipt` establishes quiescence **only of the enumerated AsyncNativeIo-tracked ownership paths**. It does not attest to independent OresScheduler source tasks, external/host-transferred resource handles, raw OS syscalls outside this broker, or file/socket operations that fail to acquire a tracked lease. Those are separate P0 integration gates. Receipts are not a signed provenance certificate or cross-process authority token.

**Actor-native registration lifetime:** treat `AsyncNativeIo.register` as an actual actor-owned physical operation from the moment a native handle is transferred to the context, even when no read/write `IoLoan` is active. Actor `DONE` must remain pending until that registration physically retires. `unregister` is NOT an untrusted physical-close receipt: an actor-owned Socket must satisfy `isClosed()`, otherwise it remains registered. Generic actor-owned `AutoCloseable` without a verifiable close witness cannot detach via `unregister` and must be retired by successful context-owned close. Deferred unregister retains both the registered-handle owner and any active loans until the last loan ends. Cross-actor ownership must be tested explicitly: one actor's registered handle and another actor's active loan are independent retention obligations. Failed TCP connect must close its socket before unregistering it; if close fails the actor registration remains retained. Context shutdown may need a second, already-sealed `ActorRuntime.close()` attempt **after** `AsyncNativeIo.close()` succeeds, because physical I/O retirement is a prerequisite for actor finalization. Run `NativeRegistrationRetirementTest` and the existing `AsyncNativeIoTest`, `PhysicalIoLoanRevocationTest`, and guest TCP/HTTP contract suites on real JDK25/GraalVM before promoting.

**Native resource admission:** `AsyncNativeIo.borrow()` now refuses any caller whose actor/worker permission delegation was revoked, before incrementing physical loan counts. `register()` refuses revoked authority before publishing new resources on an open context, but deliberately keeps the prior closed-context quarantine branch for handles created by already-running operations. Test worker-authority revocation while a job is live, denial of both borrow and registration, preservation of host-owned ordinary loans, and exactly-once handle closure. A denied registration leaves handle ownership with its caller, which must close it.

**Queued worker hardening:** `AsyncNativeIo.Operation.run` rejects a revoked actor-scoped authorization *before* entering `work.call()`. Test with one blocked physical worker, one queued work item, parent revocation before releasing the blocker, and proof that the queued callable was not invoked. Separately test that an authorized queued callable executes exactly once. The `NativeHttp` listener supervisor admission test must reject both direct actor turns and workers carrying `NativeIoAuthority`, while host-only admission remains possible. These are integration tests until actually executed at the pinned head.

**Revocation semantics:** revoking a token means no *subsequent* source actor or native-worker permission check may authorize the affected subtree. It does NOT synchronously kill the actor, stop an already-entered native OS syscall, undo prior file writes, or guarantee that an authorization/check-to-use race is closed. Full physical I/O retirement and OS-boundary fencing require separate design and runtime tests; do not count this as complete revocation or arbitrary host capability quarantine.

The checked-in 44-case `contracts/v1/scoped-permissions-v1.json` oracle is unchanged. Its
`endpoint-invalid-port` case MUST remain `deny`. Never rewrite the pinned v1
vector digest to justify accepting outbound port 0.

## Admissible execution environments

1. Source-org job with a genuinely assigned GitHub runner and recorded steps, or
   a vetted **source-local** operator runner with a clean, exact Git checkout.
2. A cross-org destination ONLY after the independently reviewed V3 private
   source transfer gate authorizes its exact numeric repo ID and carrier
   workflow. No raw connector-based mirror, temporary-ref exception, or
   token-free bypass is authorized by the present empty allowlist.
3. A source-free canary on an alternate org tests only availability, never
   compilation of this private tree.

## JDK 25 / GraalVM exact-head focused acceptance

Run from a CLEAN checkout in the private source repository on an authorized
runner. Set `EXPECTED_HEAD` to the 40-character immutable commit SHA of the
open PR just before execution, and compare it with the current PR head through
GitHub independently.

```bash
set -euo pipefail
: "${EXPECTED_HEAD:?must pin the exact current PR head}"
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
test -z "$(git status --porcelain)"
git rev-parse HEAD^{tree}
java -version        # must report GraalVM JDK 25
mvn -version
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dtest=AdversarialPermissionCeilingTest,ActorScopedPermissionDelegationTest,ActorPermissionRevocationTest,ActorPermissionPhysicalDrainTest,QueuedNativeIoRevocationTest,HttpListenerAdmissionAuthorityTest,PhysicalIoLoanRevocationTest,NativeRegistrationRetirementTest,RuntimePermissionsTest,RuntimePermissionsNetworkAuditTest,RuntimePermissionsIdentityHardeningTest,RuntimePermissionsPortZeroContractTest,ScopedPermissionInteropTest \
  test
mvn -B -ntp -Dores.runtime.carriers=native verify
mvn -B -ntp -Pnative-aot -DskipTests package
mvn -B -ntp -Pnative-hybrid -DskipTests package
```

Native Image package success is **build evidence only**, not proof that
actual Oreslang guest binaries executed native host-I/O allow/deny fixtures.
Record separately the **executed** program/output evidence for denied
filesystem reads/writes, environment reads, network connect/listen, wildcard
denials, worker-thread authority, child actor delegation and untrusted
isolate escape attempts. Native Image and strict-isolate execution must be
tested with the real compiler/runtime, not synthetic outputs.

Required receipt fields: exact source SHA and root-tree SHA, runner
identity, job/run URL, each shell step and exit status, test report totals
and failures, native executable SHA-256, exact guest inputs/expected outputs
and reviewer attestation. A job with `steps=[]`, `runner_id=0`, or an
unverified source SHA/tree has **NOT_EXECUTED** status regardless of GitHub's
final red/green badge.

## Administrator unblockers

- Source `main` is currently reported as `protected:false`.
  Set independent review, protected branch, non-empty required latest-head
  checks and no bypass. Recheck the effective live configuration.
- Check source-org Actions billing/minutes and runner eligibility for the
  actual failed run IDs; do not infer the cause from zero steps alone.
- Issue #962 tracks cross-org private-source export approval, including the
  reviewed destination allowlist and carrier contract. It remains a hard gate.

Do not merge any stacked security PR while these execution and governance
conditions remain unresolved.
