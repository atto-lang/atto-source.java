# Workflow host-service registry: trusted embedding boundary (draft)

**Status (2026-10-29):** a working **host-side Java 21 registry and runner injection**,
**NOT** a compiled/executed `.ores` guest call, Java JNI execution or
GraalVM Native Image integration. Target integration is the private
`oreslang-ai-agent-coordination-via-actors-demo` draft PR #23, which has
a working host-side bounded JNI/SQLite mailbox but no guest intrinsic.

## Scope

- `WorkflowHostServiceRegistry` is a context-local, **default-deny** port
  for immutable scalar workflow requests/replies. It has no importable
  guest Java object, native handle, SQLite path, issuer key or callback
  constructor in the guest language.
- The supported actions are `ACTIVATE`, `ACCEPT_STEP`,
  `CLAIM_DISPATCH`, `RECONCILE`, `FINISH` and `INSPECT`.
  `CREATE_RUN` remains a host-only bootstrap action. Request fields
  include request ID, action, tenant, run, Git revision, actor generation,
  activation epoch, expected durable version, step, message ID, effect
  key/digest, outcome, receipt, and an opaque capability. The embedding
  **service**, not this registry, must verify the capability cryptographically
  and enforce tenant, generation, issuer, expiry, revocation, action, effect
  digest, and receipt scope at execution time.
- `LinkedProgramRunner.runWithExitStatus(..., Service)` adds an **explicit
  trusted-host overload**; the original overload is unchanged and denies
  workflow storage. The one-shot, non-inheritable thread scope is active
  **only while `context.initialize(OresLanguage.ID)` is called**, before
  guest source execution. The first `OresContext` consumes the binding
  and clears the ThreadLocal; subsequent contexts on that thread do not
  inherit the service.
- `OresContext` owns the registry; it revokes admission and drains
  outstanding host responses before actor/native-I/O teardown. If a
  service callback stays unresolved, shutdown throws and **quarantines the
  context rather than pretending native resources are retired**.
- Registry admission is limited to 64 outstanding requests; callbacks are
  dispatched on a dedicated bounded Java host worker. An unavailable,
  overloaded, malformed, mismatched-reply, closed, or failed-host condition
  receives a scalar failure result. Host exceptions are not reflected to
  guest data.
- The **host owns the service's lifecycle and JNI handles**. Closing this
  registry does not dispose the host's native storage handle.

## Tests actually executed

`WorkflowHostServiceRegistry.java` and the standalone
`WorkflowHostServiceRegistryTest.java` compiled and executed with local
OpenJDK **21.0.12.1**:

`WORKFLOW_HOST_REGISTRY_RESULT passed=5 host_only=true`

Cases: default-deny/malformed identity; one-shot provisioning and
cross-context noninheritance; worker-only callback with bounded
backpressure and closed-state refusal; forged reply identity and
failed host denial; nested-provision refusal. After hardening the
shutdown-drain path, the same five groups passed again.

Exact Git blob SHA of the locally compiled files is identical to
the committed branch:
- production registry: `d9d3eafedeb44bab850a9bd116a8b30ca8097fc8`
- standalone test: `c0f54ebc37fe4881dd4b8c4cdfbc1bec43fe8ca1`

The standalone command `bash scripts/check-workflow-host-registry.sh`
compiles just the registry and its test. **It does not compile the
entire Oreslang compiler** or execute any guest, since the local host
does not have the authorized Maven/GraalVM compiler build checkout.
The `OresContext` / `LinkedProgramRunner` changes have been
reviewed for API/ABI integration, but are **not** compiler-tested locally.

## Release-blocking next steps

1. Add a **first-class, effect-checked workflow-service intrinsic/actor
   message channel** to the actual Oreslang compiler/evaluator and a
   production runtime binding to this registry. Never use unguarded
   `java:` imports or `getPolyglotBindings` to expose the service.
   Enforce `Sendable`, owned immutable request snapshots, and prohibit
   suspension with an outstanding `critical-read/write` state loan.
2. Implement an owner-authorized adapter connecting `Service.dispatch`
   to the demo's `WorkflowStorageMailbox.submit`. Authenticate a real
   cryptographic capability (or equivalent externally verified grant)
   and ensure the returned status is copied into guest-owned data.
3. Execute a real `.ores` supervisor through the injected service:
   durable admission → one-shot claim → kill actual Oreslang process →
   restart with new fenced epoch → verified remote receipt →
   completion without duplicate dispatch.
4. Verify full Java Maven/compiler acceptance and GraalVM 25 Native
   Image, then LLVM / .NET / WASM / Forge conformance on the same
   contract fixture. Use exact sources and nonzero-step CI logs.
5. Keep the PR **draft** until end-to-end runtime tests and independent
   review pass. No private-source mirroring to another GitHub organization
   without explicit approvals from both source and destination owners.

This PR deliberately does **not** claim to solve the final `.ores`
guest bridge or real runtime recovery. It makes the first trusted
host-side admission boundary explicit and testable instead of exposing JNI
through unrestricted polyglot interop.
