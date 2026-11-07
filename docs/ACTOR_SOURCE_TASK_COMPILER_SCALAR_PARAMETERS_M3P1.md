# AttoLang M3.1 — actor-owned SourceTask scalar parameter admission

**State:** new compiler/interpreter integration AUTHORED, NOT_EXECUTED.
Stacked on [Java PR #2638](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2638),
which integrates typed synchronous invocation frames #2630 and actor
SourceTask suspension leases #2631. This slice does **not** certify physical
stack separation, native guest stack safety or automatically captured lexical
locals/return continuations.

## Implemented, precisely

The real `OresEvalRootNode.Evaluator.SourceTask.resumeSource` now
interposes `OresScheduler.bindCompilerSourceArguments(arguments)` when
initially binding source function or method parameters. Only inside an
authenticated actor-owned scheduler dispatch, and only for a nonempty
fully supported list of up to **six** primitive wrappers, it:

1. Encodes the source function's actual parameter values into the **existing**
   64-byte, generation-fenced `ActorSourceTaskFrame` lease (4-byte length
   header plus 9-byte typed slots, exact Float/Double raw bit patterns).
2. Reads the values **back out of actor-owned storage** before passing them
   to `bindCallableArguments`. This makes the guest source parameter binding
   consume actual arena-decoded values; it is not merely a host diagnostic
   copy or metadata descriptor.
3. Marks the saved scalar image as **compiler-owned**, preventing host
   continuation snapshots from overwriting the reserved parameter region
   while this SourceTask is active.
4. At each regular resumed `SourceTask` dispatch, before executing its
   heap-owned continuation, validates that the original parameter tags,
   values, type/arity and stored bytes still match the actor-owned image.
   A mismatch poisons the actor arena and fails closed; guest catch cannot
   recover the actor generation after the budget/authentication failure.
5. The scheduler's prior M3 binding authenticates exact actor arena,
   `ExecutionContext` and active physical turn; no continuation read/write
   from a Future producer thread, another actor, a stale generation or after
   terminal task retirement.

**Explicit fallback:** empty arguments, references, string/object graph
values, mutable object state and more than six scalars **remain on the
existing JVM evaluator path** without storing partial frame state. These
are intentionally not claimed as source-lowered values. Cancellation
cleanup still follows the existing lexical unwind rather than creating a
new ordinary guest resume or passing invalid parameter checks into a
cancellation-only turn.

Typed snapshots still hold arguments only, not mutable local variables,
operand stacks, return PCs, source closure captures, `Env` chains or
deep recursive expression frames. The compiler must eventually replace
these JVM structures with a typed heap-frame instruction stream and
iterative trampoline before actor-stack process isolation can pass.

## Tests and evidence

New `SourceTaskScalarParameterBindingTest` adds **three host/runtime
JUnit methods** testing the real scheduler's typed parameter admission,
raw IEEE payload and Task identity across await; explicit fallback for
reference/wide inputs; fail-stop parameter-image mismatch while a separate
actor remains live.

New canonical guest fixture
`src/test/resources/actor-stack/source-task-typed-args-cooperate.atto`
plus `ActorSourceScalarParameterLanguageTest` (one JUnit method)
compiles an actor calling a `suspends fnc` with two integer parameters,
cooperates, then checks output `before|42|second`. Once executed this
would probe the source-actor nonreentrant mailbox boundary as well as
argument value integrity. Today it is **AUTHORED_NOT_EXECUTED**; the
previous `.atto` fixture work also lacked executed receipts.

Must run on an admitted exact-head Java/GraalVM runner:

```sh
mvn -B -ntp -Dtest=SourceTaskScalarParameterBindingTest,ActorSourceScalarParameterLanguageTest,ActorScalarSourceTaskIntegrationTest,ActorSourceTaskFrameTest,GuestScalarCallFrameTest,GuestScalarActorInvocationLanguageTest,GuestInvocationFrameTest,ActorGuestInvocationLanguageTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

Required receipt fields: full PR head SHA, Git tree identity, compiler/
GraalVM version, fixture blob SHA, actual executed job steps, runner ID,
run attempt, test JUnit XML, stdout and negative mutation evidence.
`steps=null`, `steps=[]` and `runner_id=0` are runner-admission
failures, **never** compiler-backed PASS/FAIL.

## Next implementation milestones

1. Typed compiler **lexical locals**, operands and return program counters,
   with no unbounded recursive JVM guest stack.
2. SourceTask `await` continuation values and cleanup frames in the
   arena, with explicit actor/Task generation and ownership semantics.
3. Canonical `.atto` A overflows managed guest quota, B keeps running,
   supervisor receives typed CONTROL error and restarts A; stale
   continuation fails after generation change.
4. Low native-stack subprocess and hostile JNI/JIT boundary isolation,
   cross-backend exact-head guest execution receipts for LLVM, Java,
   .NET NativeAOT, Wasm and Forge/Cranelift.

Keep this PR DRAFT and unmerged until actual compiler-backed source
execution, independent review, and appropriate CI/security gates.
