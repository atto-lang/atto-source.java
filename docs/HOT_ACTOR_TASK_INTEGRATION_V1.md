# Task/suspends × actor hot reload: current integration and release gates

**Status:** PRIVATE DRAFT integration, not production actor hot-reload and not
source-level Task execution. This branch combines existing hot-code actor
routing/proxy admission with scoped Task runtime, while maintaining the
compiler's source Task execution rejection.

## Git provenance

The integration base is logical actor proxy #1367 (which includes the
experimental SHARED-only hot-router stack #1270). The second exact parent is
Task/suspends #1411; its 38 changed source/test/grammar/doc blobs are preserved
except for the one deliberate `OresContext` merge: retain TaskScope physical
teardown and source actor's `bindSourceActorDomain(this)` registration.
The actor upgrade epoch gate carries the follow-up placement, code mode,
shared-cell digest and typed pin revisions from #1372's working branch.
Never replace the modern ActorRuntime or OresEvalRootNode with an older stacked
runtime without reviewing every concurrent change.

## Lifetime protocol, currently implemented as HOST primitives

1. `ActorTaskGenerationBridge.start`: CONTROL must first authorize actor
   identity, unchanged code-generation metadata, scope parent, capture
   ownership, actor state loans, quotas/deadline and physical sandbox. This
   bridge currently enforces only the checked actor gate's `SUSPENDED_TASK`
   lease, acquired BEFORE scheduler admission. It grants no actor-state
   capability and remains package-private.
2. A `suspends` callable uses **the same Task and lease** across a carrier
   yield. An explicit child `task {}` creates its own Task and generation
   pin. Cancelled results are not physical-exit acknowledgements.
3. On successful Task admission, `OresTaskScope` owns the lease-release
   callback until its physical `onQuiescent` barrier. Rejection before
   admission releases the unused lease exactly once; a release failure must
   remain an error and block safe image retirement.
4. `ActorUpgradePreflightV1.prepare` freezes code/child Task admission and
   physical mailbox DATA dispatch. CONTROL/shutdown continues progressing and
   immutable proxy messages may be admitted under fixed byte/message credits.
   `quiescentSnapshot` checks both the old-message watermark and surviving
   Task/code/FFI/shared-region pin counters.
5. `close` ABORTS code staging first and only then reopens the old mailbox.
   A failed code abort must leave DATA frozen and escalate; no false rollback.
6. **The joint preflight exposes no guest/public publish method.** Its
   package-private CONTROL commit hook now holds both generation and mailbox
   monitors through a single authoritative route update. Legacy standalone
   gate/ticket publications remain available to older host tests and MUST NOT
   be invoked independently by a production router.

### Why the additional safety gates matter

- Draining an actor mailbox does not prove a suspended Task is gone.
- A cancelled Future cannot release code containing a still-executing
  `finally`/defer or native FFI stack frame.
- A source-level actor's private heap/arena is **not** an OS process.
- Changing a JIT/AOT code mode does not make a private actor untrusted
  or confer a physical sandbox.
- No hot upgrade may reinterpret shared-cell state ABI or copy borrowed
  pointers across generations.
- `SHARED` source router is currently the only experimental live actor
  upgrade target; PRIVATE and UNTRUSTED replacement via protected workers
  remains fail-closed.

## Next integration milestones (in priority order)

1. Run exact-head Java/Graal JUnit and existing SHARED actor guest-source
   tests on a secure authorized runner. The workflow checks JUnit XML
   non-skipped counts for epoch, placement, Task bridge, joint preflight
   and bounded logical proxy tests. Infrastructure runs with zero steps are
   NOT passing evidence.
2. **Converge the two host-only route publication prototypes on one
   production contract.** Finish a single route CAS plus crash-recovery
   journal, real actor checkpoint/restore verification, authenticated
   `mail.done` receipts, and lossless bounded queued-message transfer.
   Never activate competing authorities together.
3. Compiler-lower owned `suspends`/Task frames with typed actor state-loan,
   deadline and cancellation/FFI lease instrumentation. Keep ordinary
   `TaskExpr` fail-closed until all ownership and physical-exit tests pass.
4. Prove real SHARED source actor v1 → v2 across outstanding Task, mailbox
   output, FFI/shared-region borrow, code-image retirement and rollback.
5. Deploy an opt-in protected PRIVATE worker with canonical, versioned
   `state {}` snapshots and stable ActorRef; enforce independently isolated
   UNTRUSTED workers/pool before code staging and capability-safe migration.
6. LLVM: authenticate offline AOT/PIC images and exercise source actor
   AOT→AOT before optional ORC JIT. Prove native frame/FFI/lease-retirement
   and failed `dlclose`/resource tracker quarantine with ASan/UBSan.

### Security and testing

The source remains in its original PRIVATE GitHub repository unless its
owner explicitly authorizes an access-controlled test-org mirror. Such a
mirror must reproduce the **exact reviewed Git tree**, independently attest
the original source head and tree, refuse public exposure or embedded tokens,
and distinguish a mirror commit SHA from the source commit SHA. Merely copying
a few test files is NOT whole-tree evidence. Both production hot-reload and
compiled guest Task execution remain disabled until JUnit/guest execution,
independent capability review, crash/fault injection and native backend
parity are available. **No test receipt can be
constructed from skipped Actions jobs.**

## Single-authority SHARED publication prototype (follow-up)

`ActorRoutePublicationV1` now provides a CONTROL-owned *host integration
primitive*, not a source-runtime activation. The immutable `Published` tuple
holds logical identity/incarnation, route epoch, code image, defensive-copy
checkpoint with SHA-256 content receipt, physical route, the new Task admission
gate, and the last old-route dispatch watermark. Only this tuple is authoritative.

A staged transaction first acquires `ActorUpgradePreflightV1` (freeze Task
admission then physical DATA dispatch). Its route commit requires a still-owned
candidate, zero old physical-work pins, drained old dispatched messages,
unchanged state schema, exact original mailbox epoch/watermark, and a distinct
replacement route. It constructs the new tuple *before* publication and swaps
one `volatile` reference under the coordinator's dispatch lock. Old Task
admission is retired before reopening queued DATA on the new route. Failed
pre-publication checks retain rollback; a post-publication cleanup failure
**quarantines** the authority rather than reverting it or dispatching old DATA.

Critically, `dispatchNext` holds the coordinator lock through trusted physical
send submission; returning a route + envelope to send later would permit an
upgrade race. Unknown send outcomes stay outstanding. The transport callback
must never block or execute guest code; actual acknowledgements must be
verified by a trusted worker before `complete` is called. The existing raw
`LogicalActorProxyMailboxV1` helpers remain independent for older tests; a
production caller MUST NOT bypass the coordinator and use them as a second
source of routing truth.

The nine `ActorRoutePublicationV1Test` cases cover atomic tuple observation,
mail+Task drain, rollback/FIFO, schema and physical-route rejection, stale
stage replay, authority loss, checkpoint immutability, concurrent admission,
and protected-kind denial. These are **authored tests, not executed JUnit proof**.

**Not yet wired into `LogicalSourceActorRouterV1`**: this requires canonical
immutable ingress encoding, authenticated delivery/completion provenance,
checkpoint validation from a real `SourceActorTransfer`, candidate-ready
attestation, and exact source actor retirement. The current host coordinator
accepts these as trusted prerequisites and cannot independently verify them.
PRIVATE/UNTRUSTED remain fail-closed. No production switch is enabled.


## Concurrent CONTROL publication prototype reconciliation (2026-10-18)

The integration branch now also carries `ActorUpgradeAtomicPublicationV1`
and ten additional `ActorUpgradeAtomicPublicationV1Test` host JUnit cases.
This path holds the generation **and** mailbox locks during its single
immutable route/state/physical/watermark swap, and requires both subordinate
epochs to agree with the authoritative record before any DATA dispatch. It
quarantines independent legacy gate/mailbox epoch changes, clones/bounds
state bytes (1 MiB), and closes its subordinate gates on terminal shutdown.

**These are two alternative experimental host models, not two production
routers to be installed together**:

- `ActorRoutePublicationV1` creates a distinct Task admission gate per
  generation and retires the old one after route publication.
- `ActorUpgradeAtomicPublicationV1` advances a single generation gate
  under the combined lock while atomically updating route and mailbox epoch.

The final design must choose one lifetime/retirement model, remove any
conflicting production entrypoints, and prove reentrant callbacks, scheduler
physical exits and crash recovery across that exact model. Neither prototype
currently proves real source actor state reconstruction or authenticated
physical-route identity. A returned delivery in the second prototype remains
outstanding through send acceptance; an ambiguous send must never be
requeued, and an untrusted completion cannot be treated as `mail.done`.

CI requires both host JUnit suites with nonzero, non-skipped XML receipts,
but the source runner has so far failed before execution. No pass is claimed.
