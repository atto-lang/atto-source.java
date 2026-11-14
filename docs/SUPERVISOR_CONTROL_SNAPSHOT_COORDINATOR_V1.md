# Supervisor CONTROL-dispatched snapshot coordination — staged host implementation

**Status: HOST_CONTROL_SUBSTRATE_ONLY.** This draft is stacked on Java [PR #2357](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2357). It uses **real** Java `ActorRuntime` supervisor registry reads and submits observation work through the existing injected VM `ControlDispatcher`. This is NOT guest Oreslang source execution, native supervisor-to-supervisor CONTROL fanout, AOT proof, or a complete actor lifecycle snapshot. All resulting hierarchy observations remain explicitly **partial**.

## Runtime boundaries

- `ActorRuntime.dispatchSupervisorObservationControl(Runnable)` is package-private and guarded by the existing host/supervisor-only and runtime-affinity checks. It reuses `ControlDispatcher` and refuses a closed runtime. The default standalone runtime deliberately has `ControlDispatcher.unavailable()`; in that configuration observation returns `CONTROL_UNAVAILABLE` rather than running work on an ordinary actor/carrier or creating a second executor.
- `SupervisorRegistryObservation.authorizeControlObservation(...)` validates a host-issued, opaque, revocable grant against the exact session object, asserted tenant, subtree root identity, registration-generation placeholder and bounded depth/nodes/deadline **before** scheduling work. Authorization is repeated inside the CONTROL turn, closing revoke/expiry/reregistration races.
- `SupervisorControlSnapshotCoordinator.request(...)` is host-only and nonblocking; it returns `CompletionStage<Snapshot>` with **no capability for callers to complete the producer Future**. A successful read produces the actual registry adapter's `partial` snapshot. Errors are stable codes without guest payloads or runtime exception details.
- Admission is limited to eight outstanding CONTROL requests globally and one per grant; an already accepted task retains its quota through actual completion, even if its caller times out, revokes, or closes. A JDK observation timeout only fails the waiting stage: it is **not** evidence of physical CONTROL cancellation. Queued jobs that eventually execute after a deadline do not capture any runtime data.
- Inline dispatcher execution on the requesting thread is rejected. Duplicate CONTROL turn attempts fail closed without freeing another physical invocation's admission slot. A dispatcher which reports failure before executing its work is required to guarantee that queued work will not later start; a late, contract-violating turn still fails to observe after its returned stage is completed.
- Revocation/close fail active stages but do not manufacture a healthy result. The execution turn is not allowed to inspect guest heaps, mailbox messages, exception strings, stack traces or mutable state.

## Host integration contract

A trusted embedder configures `ActorRuntime` with a VM-owned `ControlDispatcher` that enqueues work on its bounded CONTROL carrier. It keeps session-scoped observation grants out of actor mailboxes, JSON, URLs, cookies and browser JavaScript. The privileged issuer must independently authenticate the session and authorize its tenant and subtree; a tenant ID string is **not** authorization.

No new source-language keywords are introduced, and this does not duplicate the proposed `ObservableActor<E: Sendable>` trait. It is a constrained read-only predecessor to lifecycle-event streaming. See common-source [PR #200](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/200), Java [PR #2345](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2345), and runtime-ui [PR #5](https://github.com/ores-truffle-oreslang/oreslang-runtime-ui/pull/5).

## Evidence and missing gates

The focused JUnit suite supplies **host-based** tests for real supervisor tree enumeration, configured versus unavailable CONTROL dispatcher, negative authorization, foreign scopes, same-grant and global quotas, queued deadlines, revocation, close, duplicate/inline hazards, and guest attempts to mint or submit observation. The test dispatcher deliberately simulates bounded enqueueing; that is not OresVM CONTROL-priority evidence.

Execute `mvn -B -ntp -Dtest=SupervisorControlSnapshotCoordinatorTest,SupervisorRegistryObservationTest,ProximateSupervisorSeparationTest -DfailIfNoTests=true test` at the *exact head* with GraalVM JDK25. Verify nonzero tests, no skipped/failing suites and the checked-out Git tree. A workflow with `steps=null` proves no Java execution and is **not** a pass or an implementation failure.

Before production or merge: (1) prove actual VM-owned reserved CONTROL admission and priority under DATA saturation; (2) add runtime-supervisor incarnation tokens rather than the constant registration generation 1; (3) collect live actor lifecycle/termination receipts, bounded child replies and per-node timestamps; (4) implement authentication, server-side grant ownership and HTTP/SSE boundaries; (5) prove GraalVM AOT/LLVM/native parity separately. All absent/partial/timeout results must remain explicitly nonhealthy. Never transfer private source to another organization absent source-security/provenance approval.
