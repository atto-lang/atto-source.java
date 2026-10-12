# Oreslang dynamic actor pipelines: planner, plan, controller and runner

**Experimental API/design — 2026-10-12.** This document specifies the intended
guest language API and describes an **implemented Java host-reference control
plane** in `dev.oreslang.runtime.pipeline`. The four guest-facing types are
**not yet bound into the Oreslang parser, type checker, interpreter, Graal AOT
lowering, or LLVM compiler**. A standalone host test is not guest execution.

## Separation of responsibilities

- **PipelinePlanner**: thread-confined mutable builder; records named ordered
  actor provider closures and exact compiler-derived nominal mailbox domain.
  No providers are evaluated during construction or validation.
- **PipelinePlan**: frozen ordered layout with named stages, revision and
  trusted AOT provider identities. A plan is a host-owned capability, not a
  serializable ActorMail payload. 2..16 stages and one nominal domain for v0.
- **PipelineController**: sole authority for starting, pausing, restarting,
  installing a revised plan, hot replacement, insertion/removal and close.
  Exactly one generation is active. A paused topology update becomes effective
  only when starting the next fresh generation.
- **PipelineRunner**: narrow, generation-fenced DATA ingress; it cannot stop,
  replace, or obtain individual actor refs. Old runner instances reject sends
  after pause, restart, replacement or close.

### Intended Oreslang surface syntax (not yet compiled)

```oreslang
pub async fnc main(): int {
    const planner = new PipelinePlanner();
    planner.add("source", "Source", "PipeMessages", || -> spawn Source());
    planner.add("transform", "Transform", "PipeMessages", || -> spawn Transform());
    planner.add("sink", "Sink", "PipeMessages", || -> spawn Sink());

    const plan = planner.build();
    const controller = new PipelineController(plan);
    const runner = await controller.start();

    const first = await runner.send(Signal.Data(5));

    // Pausing closes DATA admission and returns only after the physical
    // quiescence barrier of every actor generation in the old route.
    await controller.pause();

    controller.replace(
        "transform", "TransformV2", "PipeMessages",
        || -> spawn TransformV2()
    );

    const nextRunner = await controller.resume();
    const second = await nextRunner.send(Signal.Data(5));

    await controller.close();
    return second;
}
```

The exact source-level generic types, asynchronous syntax, and whether the
provider registry is inferred statically need compiler conformance work. The
host API is currently synchronous `start()/resume()` (opening providers) and
asynchronous `send()/pause()/restart()/close()`; guest lowering must never
block an actor's single-flight lane while opening or draining a pipeline.

### Atomic lifecycle and quiescence contract

```text
NEW -> OPENING -> RUNNING
RUNNING -> PAUSING --all accepted DATA + all physical DONE--> PAUSED
PAUSED --install immutable revision--> PAUSED
PAUSED -> OPENING -> RUNNING   (new generation and new actor instances)
RUNNING or PAUSING -> PAUSED -> CLOSED
FAILED --explicit verified cleanup--> CLOSED
```

- Pause is **not** suspension in the middle of an actor DATA turn. It rejects
  new ingress immediately and drains outstanding accepted messages, waiting
  for real per-stage physical DONE. A fake Boolean `paused` is insufficient.
- Restart and hot swap are versioned **stop/drain/spawn/rebind** operations;
  they do not mutate actor pointers inside a published pipeline.
- Every runner carries a generation. Replies or callbacks from earlier
  generations must be fenced, even if names, memory locations or provider
  classes are reused. Invalid or unknown providers fail before publication.
- A source may physically finish before its sink, but no stage's routing
  fence can disappear until **that stage** physically finalizes.
- v0 supports one admitted root round trip per active generation, matching
  current ActorStream. A multi-request runner requires separate capacity,
  reply-correlation and cancellation proofs.
- If any send has an ambiguous publication outcome, fail/close the controller.
  Do not automatically retry into a new generation.
- Providers are evaluated by the trusted engine outside controller locks, not
  from actor receive callbacks. The engine owns both partial-start cleanup and
  all physical stop confirmations. Publication must bind all stage refs
  atomically before DATA admission.
- Unknown stage ID, duplicate stage name, invalid domain, stage overflow,
  or incompatible provider contract fail closed.
- State migration is **not automatic**. A future explicit
  `Snapshot<T> -> Transform<T> -> Restore<T>` protocol needs immutable snapshots,
  same-domain version checks, a migration deadline and rollback/quarantine.

### AOT and hot loading

Java/Graal Native Image and LLVM AOT builds cannot assume a fresh Oreslang
class body can be parsed, compiled, linked, and executed at any time.
`ActorProvider` is an opaque registered constructor capability, not a network
URL or unchecked guest eval source. "Hot load" in v0 means **load and activate
another registered, already-compiled actor implementation**, with no
recompilation, exactly at the physical pause barrier.

Future truly new executable plugins require a separately designed signed
component manifest, supported native module/ABI loader, capability sandbox,
schema fingerprint verification, lifecycle cleanup and admission rules. Such
plugins cannot be advertised as AOT-safe until a tested backend supports them.

### Java reference engine integration required

The committed Java host coordinator has an injected `Engine.open(plan)` and
`Session.send/stopAfterDrain/getDone` contract. It does **not** yet supply the
production `ActorRuntime` engine adapter, map the guest provider lambdas to
`ActorRef<T>`, or enable a new Oreslang intrinsic.

The current production `ActorRuntime.ActorStream` is one-flow, already bound
to concrete refs. It must not be hot-edited; a fresh stream is built per new
generation, with exact registration and whole-route DONE validation.

### Verification and release gates

1. Host coordinator tests: provider validation before spawn; 2..16 bound;
   pause fences DATA; outstanding send and physical DONE must settle; swap
   only while paused; version/generation replay rejection; restart from fresh
   providers; ambiguous delivery fails closed; idempotent close.
2. Production engine adapter tests with a real 3-actor typed Oreslang fixture:
   actual source->transform->sink->transform->source, bounded admission,
   all three physical DONE, no host fake callbacks.
3. Real guest source parser/types/interpreter support, then Graal native-image
   AOT and LLVM equivalent compiler/runtime support. Retain lexical actor
   isolation; providers may only spawn in trusted controller scope.
4. TLA+ model of ingress-pause, DONE publication, topology revision and
   old/new generation crossover; hostile concurrency and rollback testing.
5. Byte-identical source fixtures in Java/LLVM and authenticated FD3 traces
   before moving stacked PRs out of draft.

## Host integration audit and implementation (2026-10-12)

`ActorRuntimePipelineEngine` is now an additive production-`ActorRuntime`
host adapter. Its trusted provider registry checks the *exact constructor
capability identity* before invoking any provider. It requires returned
`ActorRef` objects, relies on `ActorRuntime.bindLinearPipeline` for
same-runtime, duplicate, compiler-owned closed nominal domain and
pre-DATA-admission checks, and separately requires that the bound compiler
domain identity matches the plan. It returns an immutable
`DeliveryReceipt(planRevision, stageCount)` only after the existing
ActorStream's successful full round trip **and all physical DONE receipts**.
It does **not** yet extract the source actor's typed reply value.

The engine accepts an explicit trusted CONTROL executor. Its STOP call
uses that executor and waits for every actor's *individual* physical DONE
settlement (including failed actors), without assuming route success for a
never-used pipeline. Provider invocation and restart reopening must never
run on an actor DATA carrier.

### Additional hardening

- A controller closing during an in-flight failed send must still drain the
  actor generations and must not publish CLOSED before physical cleanup.
- Restart schedules actor provider invocation through `Engine.scheduleControl`,
  not inline on arbitrary asynchronous completion/cancellation/finalizer
  threads.
- A manual `install` requires a strictly newer plan revision and the same
  exact closed nominal mailbox domain.
- A partially failing provider activation currently issues best-effort STOP
  to every known acquired ref, but **does not prove that every orphaned actor
  reached physical DONE before surfacing the error**. An authoritative
  asynchronous startup/rollback receipt is required before production release.
- The trusted executor is an injected **host responsibility**; supplying an
  inline/direct executor on a completion carrier must not be treated as
  proof of scheduler-lane isolation.
- Provider credentials are registered precompiled constructor capabilities.
  Signed dynamic plugin ingestion and typed state migration remain
  unsupported and require separate ABI and security designs.

The adapter still has no Oreslang AST/parser/typechecker intrinsics, no
end-to-end dynamically restarted guest pipeline source fixture, and no LLVM
runtime or native-image AOT evidence. Do not mistake the pre-existing 15-actor
guest regression for proof of live hot replacement.
