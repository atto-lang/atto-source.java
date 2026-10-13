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

### Additional capability audit

The production host adapter now checks `ActorRuntime.ownsActorRef(ref)` **before**
adding each returned actor to the provisional route. A provider must never
use a failed pipeline build to STOP an actor owned by another runtime.
The verification suite includes this foreign-runtime cleanup attack.

Public send, pause, restart and close completion stages are read-only
observations. Converting one to a caller-owned `CompletableFuture` and
completing/cancelling that local copy cannot publish a fabricated barrier,
admit messages, or permit a topology swap. Only controller/runtime-owned
producer completions change authoritative lifecycle state.

## Transactional startup hardening (2026-10-12)

The production host adapter now checks an actor's runtime-owned **allocation
generation** against a snapshot taken just before invoking each provider.
A provider returning an existing actor (including one allocated earlier
in the *same* plan) is rejected. Existing actors returned by a hostile
provider are **never** put into its rollback stop set.

On failure after one or more successful provider allocations, startup
issues STOP to every provisionally *acquired* actor and waits for each
runtime-owned physical DONE receipt. An exceptional DONE is a final receipt;
STOP submission alone is not. This bounded cleanup executes only on the
trusted host caller. If any DONE remains unsettled, the adapter retains the
references in quarantine and refuses further opens. In that case it raises
a distinct incomplete-rollback failure rather than claiming successful
cleanup or retrying possibly live actors.

A runtime-owned `ActorProvisioningScope` now records every successfully
registered actor created on the trusted provider-control thread. Thus
providers that allocate then throw before returning the `ActorRef` cannot
orphan an actor: rollback collects all scope allocations, not just values
returned to the planner. Each provider must allocate exactly one fresh actor;
extra actors are rejected and physically retired. Nested provisioning scopes
are rejected, and scope closure clears the thread-local capture authority.

**Scope limit:** providers that spawn asynchronously on another thread are
not supported by this synchronous AOT provider protocol; they must instead
provide a separate tracked asynchronous factory API. The isolation proof
relies on a trusted CONTROL executor and the runtime-owned actor allocation
registry. Signed component manifests and guest plugin loading remain separate
release gates.

The controller's public completion observations remain non-authoritative.
Tests distinguish partial-start physically acknowledged cleanup, a
previously allocated actor that must NOT be stopped, and an actor that is
improperly reused as the second stage of a new plan. This test suite is
host-layer evidence, not a compiled Oreslang dynamic hot-swap guest.

## First-class Oreslang source execution (2026-10-12)

The Java/GraalVM parser, type checker and evaluator now have an initial
**working guest-language** lowering for:
`new PipelinePlanner()`, `planner.add(name, || -> spawn Actor())`,
`planner.build()`, `new PipelineController(plan)`,
`controller.start()/pause()/replace()/resume()/close()`,
and `runner.send()/get_done()`. The guest source is tested in
`examples/actor-dynamic-pipeline-swap.ores`.

The **compiler checks** that each provider is exactly an expression-bodied,
zero-argument lambda containing `spawn` of a statically named actor, with
no arguments and no captured constructor code. It forbids controller use
inside actor execution, controls arity and message types, and reserves all
four pipeline built-in type names against source namespace hijacking.

The **evaluator** resolves provider names through its statically linked
`ActorCodeExecutor` and compiler-owned closed nominal MessagePack v2 domain.
It registers all nominally compatible local actor definitions at controller
construction for future AOT-static hot replacement. No new bytecode, host
class, remote URI, shared heap or guest eval payload is dynamically loaded.

**Tested source behavior:** root creates Source, Transform and Sink through
providers, executes a typed downstream/upstream round trip, awaits the
runtime-authenticated physical DONE barrier, pauses, swaps Transform for the
already-compiled TransformV2, resumes a new actor generation, executes a
second typed round trip and closes. Source prints
`DYNAMIC_PIPELINE_V1_19|DYNAMIC_PIPELINE_V2_24`.
`DynamicPipelineGuestSourceTest` additionally checks rejection of an
unsafe block/captured provider, forged controller argument, wrong arity,
and source attempts to shadow the built-in class names.

**Funded exact source test:** [GitHub Actions #38327861222](https://github.com/zed-pkg-test/java-app/actions/runs/38327861222).
Confirmed 446/446 identical Git source blobs, all 1,539 Maven tests
passed including 5 new guest source tests, host control tests and the
independent 15-actor static guest regression. An earlier candidate
incorrectly inspected arbitrary enum call receivers; exact-source CI
caught the `Signal.Data` regression, and a subsequent guarded receiver
check fixed it before green certification.

### Explicit remaining gaps

- `PipelineRunner.send` currently awaits and returns an unforgeable
  completion **receipt**, not a typed guest result value. The result 19/24
  in the fixture is observed via the source actor's output. Typed reply
  correlation needs a separate language/runtime contract.
- Only zero-arg, statically linked, synchronous actor spawn providers
  are supported. Truly dynamic provider registration, asynchronous
  factories, signed binary/component loading, and state snapshot/restore
  are not implemented.
- `pause` retires every actor; `resume` creates new instances. It does
  not preserve actor-local state and does not park a live actor mid-turn.
- A dedicated trusted root/control execution scheduler needs stronger
  attestation; current guest bridge uses a separate virtual thread for
  STOP and completion work. Runtime actor affinity and cancellation must
  be verified under all backend carrier modes.
- This tests **Java/GraalVM hosted guest execution**. GraalVM native-image
  AOT, LLVM source compiler/runtime, cross-backend identity and FD3
  causal trace parity have not yet been verified.

## Audit hardening: typed delivery receipts (2026-10-12)

The earlier guest type checker advertised `runner.send(message)` as
`Future<Unknown>`. This incorrectly allowed compiler inference to treat
a successful physical delivery/roundtrip receipt as an arbitrary typed
source-actor reply value. The v0 API now types it as
`Future<PipelineDeliveryReceipt>`. The receipt is sealed to runtime
issuance: guest classes/aliases/modules cannot hijack its name and guest
code cannot construct it with `new`. It remains an opaque completion
token, **not** `Future<int>` or a typed `Signal.Result` payload.
Negative source tests reject converting an awaited receipt to `int`
and forging the receipt type.

The separate exact-source GraalVM Native Image gate
[run #38328150168](https://github.com/zed-pkg-test/java-app/actions/runs/38328150168)
passed in **both AOT and hybrid modes**, executing the dynamic 3-actor
swap fixture and existing static 15-actor guest inside each compiled binary.
That run verified the earlier 446-file guest source tree; this newer
receipt-type compiler hardening requires its own subsequent exact-tree
regression gate.

Future typed pipeline results need an authenticated per-ingress reply
correlation contract (request id, expected nominal result type, cancellation
and late-reply fencing). Until that is implemented, reading a completion
receipt as an application payload is a compile-time error.

### Declaration namespace closure

The follow-up audit extended reserved pipeline type-name admission to
**enums, nominal pattern declarations, functions, and interfaces**,
in addition to classes, aliases and modules. This blocks source code
from constructing a separately declared nominal value named
`PipelineDeliveryReceipt` and attempting to pass it through the
runtime-issued receipt type contract. Guest negative regression tests
cover the enum/pattern/function declaration paths explicitly.


## Typed guest result follow-up (2026-10-12; supersedes earlier receipt-only sections)

The Java host adapter no longer synthesizes `DeliveryReceipt` as the value of
`runner.send`. On the source actor's final admitted backward receive turn,
`ActorRuntime.endCurrentActor()` obtains the actual compiler-codec-decoded
nominal tagged message, re-encodes it under the route's immutable closed-domain
codec, and retains an owned frame. The runtime requires that frame **and**
whole-route physical DONE before the result is observable. Decoding is
scheduled on the trusted host CONTROL executor, off actor DATA carriers.

Guest syntax now additionally accepts the **explicit expected reply case**:

```oreslang
val reply = await runner.send<Signal.Result>(Signal.Data(5));
```

The compiler checks that both the sent and expected reply cases are portable
nominal enum variants sharing a declared closed domain. The runtime independently
compares the *actual* returned tagged case against the expected qualified enum
case; a mismatched reply fails the guest future. Bare `runner.send(...)` has
a sealed opaque `PipelineTypedReply` result, not `Future<Unknown>` and not
a fabricated delivery receipt. The old `PipelineDeliveryReceipt` name remains
reserved against source forgery but is no longer the send-result contract.

This is an explicitly typed expected-result **v0** API, not inference from an
outbound `Signal.Data` into a `Signal.Result` without an annotation. The
compiled provider domains are checked again on actual `ActorRuntime` binding.
Request correlation for multiple concurrent flows is not yet supported.

**Release evidence:** the host implementation and guest positive/negative tests
are committed on PR #779, but no passing Maven or GraalVM native-image run has
been obtained for these new commits. The earlier receipt-only exact-tree CI
runs cited above validate their older trees only and must not be relabeled as
proof of this typed-result change. True state-preserving pause/resume is also
not implemented: current `pause` retires the stage generation and `resume`
spawns replacements. Source-compiled LLVM parity remains a separate gate.

## Security audit: exact bound-domain result admission (2026-10-12)

The explicit result-case checker previously accepted two cases from the same
enum even if they were not jointly present in any closed mailbox pattern.
It now requires an explicitly declared common closed pattern. Runtime proof is
stronger: `PipelineRunner.sendExpected(message, case)` invokes a side-effect-
free `Session.requireExpectedReplyCase` against the **actual bound**
`TaggedMessageCodec`, *before* reserving the single-flow ingress. An engine
without such a compiler-codec attestation fails closed. The controller
rechecks the generation and running state after external validation, so
a concurrent pause/restart cannot publish into a retired actor generation.
Invalid expected cases leave the controller RUNNING and preserve admission
credit; valid explicit result requests still check the final actual tagged
case after whole-route physical DONE.

The return-type AST rejects inferred/nested arguments such as
`send<Signal.Result<>>`. Bare `runner.send(message)` produces the sealed
opaque `PipelineTypedReply`; member access is rejected rather than falling
through to dynamic `Unknown` member typing.

**Semantics:** This v0 result is exactly the authenticated **backward mailbox
message received by the source**, not any new value that the source computes
while handling that message. For the existing 3-stage source fixture, the
typed returned frames are `Signal.Result(14)` and `Signal.Result(19)`,
while the source separately prints totals 19 and 24. A final value produced
*by* the source requires a separate explicit authenticated source-completion
intrinsic/protocol; inferring it from prints or state would be unsound.

The guest tests now distinguish the backward frames from the derived output,
reject a same-enum-but-out-of-domain result case, reject inferred/forged
result types, reject opaque member inspection, and assert invalid bound
reply cases never publish DATA or consume ingress credit.

**Evidence status:** these hardening changes are committed but still have no
passing exact-source Maven or GraalVM Native Image AOT/hybrid CI evidence.
The earlier green runs validate earlier receipt-only trees. Java directional
phase admission is still not wired as a reservation/commit at the actual
mailbox publication linearization point. State-preserving `pause()/resume()`,
source-final produced results, LLVM guest codegen, and cross-backend traces
remain release blockers.

## Publication and public typed-result closure (2026-10-13)

**Java scheduler publication:** Both root and directional messages now use an
explicit provisional phase `-2 - expected`. A directional publication
reserves that phase while serializing the nominal MessagePack frame and
performs the advancing CAS **only after** `cell.mailbox.tryWrite(envelope)`
succeeds. Both mailbox publication and CAS run while holding the recipient
`lifecycleLock`; its dispatcher obtains the same lock before `tryRead`,
so a newly enqueued DATA envelope cannot execute before its claimed route
phase is committed. The single-flow provisional claim also rejects
concurrent/replayed publications before encoding. Prepublication root
rejections CAS their own reservation back to 0, never reviving an aborted
route. Failed directional publications and ambiguous failures after a
successful write permanently poison the route. A successfully enqueued
envelope becomes mailbox-owned immediately, including if a fatal stage
cancels the claim before phase commit; the exception path must never
release the already-queued envelope twice.

**Host type attestation:** `PipelineRunner.sendExpected` now validates the
*actual* terminal tagged reply inside `PipelineController.submit(...)`
before completing the public Future. A wrong in-domain result marks the
entire generation `FAILED` rather than leaving a physically retired
pipeline `RUNNING` and returning a false typed success. This complements
the guest evaluator's independent checked case comparison.

The dynamic integration regression now includes both a fatal transform
after a successful downstream publication and an actual in-domain
`Signal.Result` returned against an expected `Signal.Data`. Neither
is allowed to settle typed success.

**Verification:** Latest Java PR #779 requires exact-source Maven and Graal
Native Image AOT/hybrid CI. Source-org GitHub Actions report failure
without available steps. The Java changes above are committed but **not
yet empirically verified** by successful latest-head CI. Do not infer
Java correctness from LLVM native contract CI.
