# Placed CPU/GPU Tasks — Java source implementation and remaining device gates

Status: **Synchronous CPU regions and asynchronous CPU Task source lowering are implemented on the stacked Java Task draft; GPU task and eager GPU region placements are parsed but rejected by type checking and runtime until verified device execution exists.** No GPU device backend is connected. This branch is stacked on Java PR #1468, not merged to main.

## Canonical source surface

```ores
let int eager = cpu { return 40 + 2; }; // executes immediately, result is int
cpu { log_metric(); };                 // synchronous effect-only CPU region
let Task<int> ordinary = task { return 42; };
let Task<int> cpu = cpu task { return 42; };
let Future<int> compatible = cpu task { return 42; };
let int result = await cpu;

// Recognized as GPU work; both forms fail closed until device backend release:
gpu { kernel_write(deviceBuffer); };
let Task<GpuBuffer<float>> device = gpu task { return kernel(); };
```

- `cpu task { ... }` reuses the existing owned and scheduler-admitted Task path in ordinary/root execution; there is **no new parallel threadpool** and no promise of a particular physical CPU core. Explicit CPU task offload from actors is rejected until isolated captures and off-actor placement are proven, rather than silently executing the work as an actor turn.
- `task { ... }` remains the canonical default CPU placement. The new `cpu` prefix is useful in heterogeneous graphs and makes intent explicit.
- `Task<T>` and `Future<T>` both lower to `OresFuture<T>` at runtime; an explicit, contextual `Future<T>` binding is accepted. Inferred handles remain `Task<T>`.
- Submission enqueues initial work (it does **not** suspend the caller). `await` suspends the *awaiting* logical continuation and schedules its resumption on its owner scheduler, including for already-settled Futures.
- `rt cooperate` and safe source loop cooperation checkpoints are the CPU yielding operations besides await. Yielding does not change Task/actor ownership or run guest continuation on the Future-settling thread.
- Captures require compiler joined ownership admission proof and physical-lifetime retirement; moving owned captures into an asynchronous Task is not equivalent to borrowing locals across suspension.

## Synchronous CPU region contract

`cpu { ... }` is an eagerly evaluated **inline host CPU** expression, not a scheduler offload. It produces `T` only when every reachable path has an explicit `return T;`. An effect-only region may complete normally and has type `void`. There is **no automatic Task, Future, device transfer, or scheduling yield**. Returns are region-local; enclosing callable returns are unaffected. The runtime uses a tail-call barrier for inner `return call();` so TCO cannot accidentally return from the outer callable.

The compiler checks the block under an isolated return type scope and disallows `await`, `rt cooperate`, or calls explicitly marked `suspends`. Borrowed results and unknown result shapes cannot escape. The ownership pass checks moves and borrows against the caller's live scope; defer/finally cleanup runs on the region's own exit. The runtime requires the bound type-check proof and verifies that a region does not contain an operation known to suspend before executing.

CPU synchronous region calls from actors run **inline within the existing actor execution lease**; this spelling does not authorize off-actor scheduling or violate the actor single-flight rule. The separate `cpu task` spelling is explicitly rejected inside actors until off-actor transfer is proven.

## Preserved fail-closed regions

`gpu { ... }` parses as a distinct eager GPU region but is rejected by type and runtime checks until a real device backend exists. `nb cpu { ... }`, `nb gpu { ... }` and `quantum` remain rejected at source preflight until compatible graph/effect/backend lowering is landed. `gpu task { ... }` is parsed as an explicit GPU Task AST but type checking rejects it with an exact no-fallback diagnostic. Runtime rejects the same AST before consuming Task capture ownership, independent of compile-time checking. No CPU emulation silently satisfies GPU placement.

`gpu { ... }` previously meant a zero-argument anonymous GPU callable in an older, unmerged branch; the CPU/GPU v1 proposal chooses eager regions and instead requires `gpu () { ... }` for callable kernels. Do not enable both interpretations without migrating the prior fixtures.

## GPU device implementation gates (not yet met)

1. **Syntax/AST/typing:** eager GPU region AST is now distinct from CPU, but device-effect restrictions, complete GPU type checking, a device call graph and explicit geometry/SIMT mapping still need implementation. CPU eager expressions are represented separately; cross-domain execution graph is not enabled.
2. **Executable device backend:** approved OpenCL/target backend capable of launching real kernels, with opaque `GpuBuffer<T>`, owned transfers, layouts, alias admission, resource lifetime and device capability discovery. Missing devices fail explicitly.
3. **Nonblocking completion:** a real GPU stream/fence/event drives one `OresFuture<T>` settlement from a host completion reactor; do not block actor or CPU scheduler carriers while waiting. Completion never runs arbitrary guest code directly.
4. **Safe teardown:** cancellation is intent, not device quiescence. All buffers/transfers/permits stay alive until the GPU fence is physically complete, including device-loss and canceled/late-completion paths. Keep credits and TaskScope lifetime bound to actual completion.
5. **Cross-domain graph:** nested CPU/GPU segments lower to ordered host/device continuation and transfer nodes. No device-to-host pointer escape, callback into a host actor from a GPU kernel, or implicit fallback.
6. **Native parity:** explicit LLVM/.NET/Forge/Wasm gates; accepted syntax must not lead to silently scalar-executed GPU tasks in a backend without kernels.
7. **Verification:** full exact-head Java/GraalVM/native-image regression suites, real device kernel and error-path integration tests, sanitizer/ownership stress and parity conformance. Do not mark this PR release-ready until these execute on authorized infrastructure.

Related existing drafts: Java Task #1468, CPU/GPU contract #1096, CPU host graph #1125, native owned graph #507. Do not export private repository trees to outside organizations for CI without the source export policy's explicit approvals.

## Java tests in this branch

`PlacedTaskLanguageTest`: source/guest tests for CPU `Task<T>`, inferred `Task<T>`, explicit `Future<T>`, eager `cpu { }` values, return isolation, effects-only blocks, missing return/type mismatches, suspension rejection, GPU fail-closed task and eager region placement, nested CPU Task captures, and actor CPU Task offload denial. These are checked-in tests, **not claims of executed CI**.

## Owned bounded Stream<T> Task handoff (v1)

The compiler now recognizes a distinct built-in `Stream<T>` constructed with
`Stream.new<T>(capacity)`. It uses a dedicated Oreslang-owned `OresStream<T>`
runtime handle enclosing the existing bounded, synchronized channel coordinator.
This is **not** a `Channel<T>` type alias, a Java `InputStream`, an
`Iterator<T>`, or an implicit conversion from `AsyncGenerator<T>`.

```oreslang
fnc calculate(): Stream<int> {
    val Stream<int> output = Stream.new<int>(2);
    val bool inserted = try writech(output, 42);
    return output;
}

pub routine main(): void {
    let Stream<int> stream = await cpu task { return calculate(); };
    let Option<int> item = try readch(stream);
    stdio.stdout.write(item.unwrap());
    stream.close();
    return;
}
```

**Lifetime:** completing the constructing Task does not implicitly close the
stream or invalidate its queue, coordinator, or pending operations. The handle
remains valid in the receiving task's execution context until explicitly
closed or the owning runtime is torn down. Producers and consumers may share
the synchronized transport handle in an admitted local async execution domain.
Buffers require an explicit positive capacity capped at **65,536** items per Stream; construction fails rather than silently clamping an oversized request. Existing ChannelRuntime admission limits also bound outstanding operation registrations. This is a per-handle admission bound, **not** proof of a full per-isolate byte quota.

**Delivery:** each enqueued item is delivered at most once (competing readers
consume from the same queue); ordering follows the channel coordinator.
This is *unicast/work-queue*, **not** multicast/broadcast, replay, or reactive
`Observable` semantics. `try readch` returns `Option<T>`, `nb readch`
returns `Future<T>`, and `readch` follows existing scheduler/actor blocking
restrictions. The analogous `writech` forms retain channel admission and
backpressure. Stream construction is immediate; no producer is implicitly
started just by calling `Stream.new`.

**Teardown:** `stream.close()` preserves buffered values for later reads,
then closed operations fail with the existing ChannelClosedException.
Closing the stream does not claim that an independent producer Task's physical
execution has quiesced; producers must be supervised/joined separately.
Cancellation of one waiter is not cancellation of the whole stream. A producer Task failing without explicitly closing the stream can leave future reads pending; producer supervision and failure-to-close wiring still require a separately verified structured-lifetime API.

**Compiler proof:** the payload is a concrete, data-only, owned `Send` type.
The Stream handle is an intrinsically trusted, synchronized `Send` transport,
not an arbitrary `Sync`, actor/mailbox message, borrowed view, or host
capability. Bare `Stream`, borrowed payloads, `Stream<Future<T>>`, nested
stream handles and unresolved/nominal-unverified payload types reject.
`Task<Stream<T>>` and `Future<Stream<T>>` are valid checked Task results;
a Task returning such a handle does not imply that all stream items have
arrived. Passing an existing stream to an async callable requires an explicitly
declared `Stream<T>` parameter at runtime.

**Not yet supported:** implicit `Iterator` / `AsyncGenerator` promotion,
general stream operators, arbitrary host streams, device-resident streams,
GPU stream/fence completion, typed actor stream messages, or portable LLVM and
other backend lowering. These require separately audited lifetime/effect
contracts, not name-based Send exceptions. Full exact-head executable tests
remain blocked by authorized CI runner availability.

New `TaskStreamReturnTest` exercises awaited `cpu task` stream return,
retained buffered values, a `Future<Stream<int>>` handle, bounded producer
capture, malformed payload denial, explicit Channel/Stream distinction,
bare/borrowed stream denial, and runtime capacity refusal.

### Stream Task completion runtime hardening (October 20)

The scheduler-owned source Task retains the compiler-bound `checkedResultType()`
for the exact `TaskExpr`. For `Stream<T>` results it verifies the actual
runtime result is an `OresStream<?>` before completing the Task's Future,
including normal/fallthrough completion. A `Channel<T>`, Java host object,
non-stream value, or missing return cannot impersonate a promised Stream.
Ordinary function return shaping independently validates `Stream<T>`.
This runtime check supplements rather than replaces the compiler's payload
Send/ownership proof.

`Stream.new<T>(capacity)` delegates to the single tested
`OresStream.fromGuestCapacity` runtime authority: valid signed integral
guest representations are accepted only when exactly convertible into the
bounded range `1..65536`; floating-point, fractional, truncated,
oversized and nonnumeric host/forged-AST inputs are rejected.

**Test boundary:** The production source repo has no approved private CI mirror
destination and its official `main` is not protected. The draft mirror policy
is not approved. Do not use the cross-organization tree-copy shortcut to
bypass that policy. A local JDK 21 isolated compile of the exact current
`OresStream.java` against a deliberately minimal `ChannelRuntime` stub
passed 18 capacity/close assertions; this **does not validate production
ChannelRuntime, Graal interpreter, Maven or source Task integration**.
The checked-in JUnit tests still require real exact-head CI execution.

### Nested transport Send proof — fail closed

`ThreadSafetyTraits` now independently rejects a `Stream<T>` payload
that contains another `Stream` or `Channel` handle, including inside
`Option`, `Result`, lists, tuples, records and unions. Trusting the
inner intrinsic as `Send` does not grant the outer stream data-only
authorization. Depth and node-count limits fail closed on adversarial
type graphs. The public type checker separately validates resolved nominal
class fields and rejects borrowed, unknown, and other non-data-only
payloads.

Added a source-runtime stress regression racing `close()` with cancellation
of a pending bounded writer. The required invariant is that a buffered,
already-committed item remains readable, the canceled/closed pending
writer's item is never committed, and no registration remains live. This,
like the other JUnit regressions, requires real exact-head CI to establish
passing production behavior; merely committing the test is not a pass.
