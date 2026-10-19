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
6. **There is deliberately no publish method on the joint preflight.** The
   underlying generation gate and mailbox currently have separate epochs.
   Independently committing them is non-atomic. Only a single authoritative
   logical ActorRef publication transaction should commit image, validated
   single `state {}`, route epoch, message watermark and active physical
   actor as one indivisible decision, then retire old resource images.

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
2. Have **one CONTROL-owned route coordinator** enforce an atomic generation
   and mailbox route cutover. Introduce a single route CAS or transaction
   journal and recover safely after a post-CAS worker crash; do not expose
   independent gate/ticket publication to guest code. Require authenticated
   `mail.done` receipts and lossless bounded queued-message transfer.
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

The source remains inside its original PRIVATE GitHub repository. No
cross-organization mirroring is authorized by this draft. Both production
hot-reload and compiled guest Task execution remain disabled until trusted
source-owner runner evidence, independent capability review, crash/fault
injection and native backend parity are available. **No test receipt can be
constructed from skipped Actions jobs.**
