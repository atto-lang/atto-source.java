# Workflow host-service registry: security hardening and verification

Status: **draft audit**, October 29, 2026. This branch stacks on
[Java source PR #2262](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2262).
No guest-language or GraalVM Native Image integration is claimed.

## Security findings and remediation

1. **Admission-accounting race:** a nonstandard `CompletionStage` can
   register a completion handler and then throw from `whenComplete`.
   The original code released one outstanding permit in the exception
   handler, then released it again when the previously registered handler
   completed later. The duplicate release could make `inFlight` negative,
   bypassing the 64-request bound. The repair adds a per-request
   `AtomicBoolean` guard so all exit paths release exactly once.
2. **Stale provisioning-scope erasure:** the original
   `provisionForHost()` close action unconditionally removed the
   `ThreadLocal` value. After its provision was consumed, closing that
   old scope could delete a newer provision on the same thread. Scopes
   now bind to a specific provision identity and owning thread, and
   only remove that same pending provision.
3. **Thread-name spoofing at shutdown:** the original `close()`
   relied on the caller's thread **name** to reject self-join. Any
   unrelated host thread with the same name could spuriously
   quarantine a registry. The repair compares the real worker
   `Thread` reference; it also records a separate retirement
   watermark so a failed close may be retried once the outstanding
   callback has resolved.
4. **Untrusted context provisioning:** `OresContext` originally
   consumed the pending host-service authority before it had loaded
   its isolate policy. The new construction sequence determines
   `isolatePolicy` first and calls
   `WorkflowHostServiceRegistry.forNewContext(!isolatePolicy.adversarial())`.
   An adversarial context consumes/discards an attempted provision
   rather than gaining access or leaking it to a later trusted context.
5. **Public runtime registry exposure:** removed the public
   `OresContext.workflowHostServices()` accessor. The unfinished
   guest intrinsic must be an explicit typed, permission-checked API;
   general Java interop must not expose a native authority object.

Existing controls remain: default-deny unprovisioned contexts, immutable
request/reply data, bounded host-worker execution, denial of nested
provisions, redaction of host exceptions, and context quarantine if
pending callbacks cannot be drained.

## Real local execution evidence

Local OpenJDK **21.0.12.1**, standalone compilation of the registry
plus its adversarial test source:

`WORKFLOW_HOST_REGISTRY_RESULT passed=9 host_only=true`

**40 consecutive complete runs** passed. New regressions assert a
malicious completion stage cannot double-release its slot, a consumed
scope cannot erase a newer host provision, shutdown uses actual worker
identity rather than thread name, and adversarial contexts discard
a pending service. The original five cases still pass.

The committed Git blob IDs were checked against the exact locally
compiled/tested bytes:
- `WorkflowHostServiceRegistry.java`
  `6c50ab6c5c8c925a13a680b10f13f86cd2dda358`
- `WorkflowHostServiceRegistryTest.java`
  `099ad0060673c27e3cea70bdf8d52d8d115e0881`

The standalone script also contains **source-level assertions** that
`OresContext` enforces the adversarial deny gate, exposes no public
registry getter, and the runner provisions only during explicit
`context.initialize(OresLanguage.ID)`. These assertions are **not**
a substitute for a full compiler/Truffle build and were not executed
as a complete script on this host (only the standalone Java sources
were compiled and run).

## Runner/mirroring evidence and confidentiality gate

- [Source-org Actions run 40934136473](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40934136473)
  at parent PR head failed with **zero executed job steps**, runner ID
  zero. This is consistent with runner admission failure, but GitHub
  billing/minutes exhaustion cannot be proven from these run records.
- Read-only inspection of **private** alternate carrier repos:
  `akrion-sim/akrion-sim-e2e` latest run 40812378019;
  `voxletra/voxletra-e2e` latest run 40815625646;
  `scintilla-run/scintilla-run-e2e` latest run 40902650232:
  all concluded failure and their selected jobs reported **zero steps**.
  None is evidence of available minute budget.
- [Security incident #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
  remains open and expressly says **private-source export remains
  forbidden** pending independent review and authorization. Merely
  copying bytes via GitHub's connector without tokens does not change
  confidentiality. No source tree or private blobs were copied to
  another organization during this audit.

## Required release gates

1. Run the complete **exact-head** compiler/Maven/JDK25/Truffle suite on
   an authorized runner with confirmed executed job steps; add explicit
   integration tests for context construction, policy denial,
   `LinkedProgramRunner` service injection and teardown.
2. Add typed guest-language service calls, an actor-message boundary,
   compile-time ownership/critical-loan gates and a genuine Oreslang
   guest execution fixture; do not expose raw Java/JNI objects.
3. Link the demo's `WorkflowStorageMailbox` into a trusted host
   adapter with independent signed-capability admission. Execute a
   real `.ores` process kill/restart and authenticated receipt recovery.
4. Obtain separate independent security approval before any
   cross-organization private-source mirroring and verify the intended
   destination is private. Do not infer approvals from prior carrier use.
5. Keep both parent and hardening PRs **DRAFT**, without merging until
   the release criteria and independent code review pass.
