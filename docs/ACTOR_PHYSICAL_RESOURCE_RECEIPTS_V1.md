# Actor physical resource receipts — Java runtime draft

Date: 2026-10-22. Stacked on Java actor STOP cleanup [#1936](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1936).

**Implementation stage: registry and actor-finalization integration only.**
This is NOT evidence that TCP/HTTP/FFI/mutex native drivers already use these tickets,
or that full GraalVM JDK25 tests have run successfully.

## Ownership and lifecycle

- Every `ActorCell` contains a unique, private `ActorPhysicalResourceReceipts`
  registry. Driver-side tickets are not guest Futures or `AutoCloseable`:
  `Future.cancel` and an ordinary guest `close()` call cannot mark them settled.
- A trusted actor-runtime driver must register a typed ticket
  **before physical grant, handle, or FFI loan publication**, and call
  `completeFromDriver` only after the actual release/handoff is reclaimed.
  For an unrecoverable release error, call `failFromDriver`.
- Ordinary ticket admission seals atomically under the actor lifecycle
  monitor when STOP begins. After STOP only the existing actor's authenticated
  SourceTask cleanup turn may mint additional resource-close tickets.
- Both the actor execution lease and the existing ActorCell identity are
  verified at runtime admission; old tickets never move to a replacement
  incarnation, and duplicate/late settlements are inert.
- Ticket count has a per-actor bound (4096). Failed physical releases and
  receipt-overflow permanently quarantine; neither `ActorRef.done`, parent
  supervisor termination, nor private memory-slice reclamation may report
  successful retirement.
- STOP finalization checks zero pending physical tickets and zero prior
  receipt failures, **in addition to** task/continuation quiescence,
  cleanup Future notification receipts, child actor DONE and existing
  Task/mutex retirement checks. A receipt does not replace those checks.
- The registry has no background worker pool, actor-local heap sharing
  model, cross-process token or global table. It does not mandate AoS/SoA/
  AoSoA/ECS layout.

## Actual pending wiring

The Java runtime now exposes the package-internal
`ActorRuntime.registerCurrentActorPhysicalReceipt(Kind)` for trusted
runtime drivers and their JUnit fixtures. The enum distinguishes
`MUTEX_HANDOFF`, `TCP_HANDLE`, `HTTP_HANDLE`, `FFI_LOAN`,
`ASYNC_ITERATOR`, `RESOURCE_CLOSE`, and `OTHER`.

**Not yet wired:** every real `OresMutex` acquire/handoff, TCP/HTTP native
handle, FFI loan, iterator close and generation-checked provider release
must call this registration at its own *physical publication point*,
then settle it only after the respective physical reclaimer finishes.
An unused registry does NOT prove the system's actual native loans are safe.

## Adversarial JUnit coverage committed, not yet executed on JDK25

`ActorPhysicalResourceReceiptsTest` covers:
- exact-once physical ticket settlement; denied ordinary admission after STOP
- error settlement permanently quarantines even with zero outstanding tickets
- actor cancellation while a mutex ticket, TCP ticket and FFI ticket are live
- cleanup-only SourceTask minting an additional post-STOP resource-close ticket
- parent actor cannot publish DONE while a structured child has an FFI ticket
- stale old-incarnation ticket cannot poison or resurrect the successor actor
- failed private-actor physical release keeps arena and actor DONE quarantined

The focused exact-head workflow includes this class for Java/native carriers.
The private source CI destination policy on protected `main` has zero
approved external mirror targets, so no unreviewed cross-org Git transfer
may be used to claim execution evidence. Review/allowlist an exact destination
and record successful Maven/JUnit reports before merging.

## Additional release gates

1. Differential Java/LLVM actual driver lifecycle traces and scheduler-ticket
   integration; the native registry in LLVM #869 remains a separate draft.
2. Fault-injection around physical handoff publication, after driver close but
   before receipt notification, repeated STOP/crash, and simultaneous
   producer/consumer callbacks.
3. Require driver acknowledgements to be capability-authenticated. A
   physical ticket's settlement method is package-internal, but this alone
   does not prove every backend/FFI driver owns and returns proper authority.
4. Force-isolated cancellation and wedged/stuck FFI close must quarantine,
   rather than silently settling missing receipts.
5. Execute source-level nested `defer`/`finally`, `await`, async iterator
   close and supervision transitions, not only host mocks.
6. Audit registration errors and listener re-entrance in the context of each
   real physical handle backend. Do not mark this PR mergeable-for-production
   until those integration gates pass.
