# Draft: Oreslang workflow host-service intrinsic

Date: 2026-10-29. Stacked on
[security PR #2264](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2264).
This is **compiler/runtime source integration**, NOT an executed guest/SQLite
recovery proof, and it is not merge-ready.

## Guest spelling and contract

`rt workflow_request(request_payload)` is a compiler-owned `rt`
operation, **not** a freely shadowable `java:` import or a general FFI
escape. The request payload is a fixed **15-position list of scalars**:

1. request_id: String
2. operation: String (ACTIVATE, ACCEPT_STEP, CLAIM_DISPATCH, RECONCILE, FINISH, INSPECT)
3. tenant: String
4. run: String
5. source revision: 40-hex String
6. actor generation: int
7. activation epoch: int
8. expected durable version: int
9. step_id: String
10. message_id: String
11. effect_key: String
12. digest: String
13. outcome: String
14. receipt: String
15. opaque capability: String

The parser rejects a directly written list with a field-count other than
15; `TypeChecker` requires a list-like argument and returns
`Future<List<unknown>>`; `OwnershipChecker` checks suspension borrows
and rejects borrowed/guarded payloads. `WorkflowGuestPort.decode` rejects
wrong runtime arity, invalid operation or non-scalar values. The underlying host registry validates
identity and effect fields; a trusted external issuer must check capability
signatures, generation, expiration, tenant and per-effect authorization.

The operation produces a runtime-owned `OresFuture<List<scalar>>`.
The immutable reply list is:
`[request_id, status, epoch, version, prepared, in_doubt, committed]`.
Host-side errors use scalar statuses and sentinel counters; the Java service,
JNI handle, authorization callback and filesystem path never appear in replies.

**Type-system limitation:** the protocol is currently a **runtime-validated
fixed-shape scalar list**, rather than a compiler-enforced nominal
`WorkflowRequest`/`WorkflowReply` type. Static checking of every field,
`Sendable` and actor loan-across-await still requires implementation.
Do not describe this draft as fully statically typed or zero-copy.

## Permission and actor policy

- Added independent `IsolatePolicy.Capability.WORKFLOW_STORAGE`; it is
  deliberately absent from `IsolatePolicy.developer()` and untrusted
  baselines. The compiler `CapabilityChecker` requires it for this
  intrinsic. An explicit trusted host must supply the matching policy.
- `OresContext.submitWorkflowGuest` also re-checks the **effective
  current actor + context capability** on each call, rejects adversarial
  isolates and UNTRUSTED actor turns, then submits to the existing
  context-local default-deny host registry.
- No service is provisioned by default. `LinkedProgramRunner` still
  requires its separate explicit host-supplied service at context
  initialization. There is no guest-accessible `wf_open`/CREATE_RUN API.
- The evaluator rejects submission while a guest `MutexGuard` is live.
  A complete verified `critical-read/write` loan-across-call gate is
  still required. Asynchronous cancellation must **never** cause blind
  re-dispatch of an in-doubt external effect.

## Added verification gates, NOT YET EXECUTED

- `WorkflowRuntimeIntrinsicTest` checks parser acceptance, inline
  field-count rejection, explicit `WORKFLOW_STORAGE` compiler admission,
  and refusal of unknown runtime operations.
- `WorkflowGuestPortTest` checks scalar decoding, rejection of arbitrary
  Java objects as payload fields, asynchronous immutable reply mapping,
  and default-deny unprovisioned registry status.
- `examples/workflow-host-request.ores` is a source smoke fixture; it
  does NOT claim to have executed as an Oreslang guest.
- `.github/workflows/workflow-guest-intrinsic.yml` is a
  dedicated exact-head JDK25/Maven gate with targeted JUnit tests,
  `OresMain --check`, and a guest execution smoke with no provisioned
  service, using the supported `--allow=WORKFLOW_STORAGE` spelling. Hosted jobs in this source
  organization previously failed before worker admission, so an Actions
  run without nonzero executed steps cannot establish test passage.

## Next actual acceptance milestones

1. Run the complete clean pinned-head compiler build, JUnit tests and
   `examples/workflow-host-request.ores` under GraalVM JDK25. Capture
   compiler SHA, guest stdout/status and executed CI job steps.
2. Replace runtime-checked positional fields with source-level nominal
   `WorkflowRequest` and `WorkflowReply` types plus effect/borrow
   checker rules; forbid holding actor critical state loans across
   workflow submission or await.
3. Provide a **trusted host** implementation of `Service.dispatch`
   backed by the demo repo's
   [WorkflowStorageMailbox PR #23](https://github.com/ores-truffle-oreslang/oreslang-ai-agent-coordination-via-actors-demo/pull/23).
   Authoritative admission, signing, provider identity and receipt
   validation are not implemented by this compiler PR.
4. Execute a real .ores actor against the JNI/SQLite driver, kill the
   entire runtime after an externally committed effect, then restart
   under a new fenced epoch and reconcile without redispatch.
5. Verify GraalVM Native Image and then LLVM, .NET, WASM and Forge
   parity. Keep this PR and its parents **draft** until all requested
   execution evidence and independent security review exist.

Do not copy private sources to another org: the open
[security incident #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
forbids unauthorized private-source export. A token-free mirror is still
a cross-organization source transfer.
