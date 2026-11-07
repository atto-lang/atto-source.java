# AttoLang M3.2 — bounded source lexical scalars and call/return ABI

**Status:** implementation and tests AUTHORED; hosted exact-head Maven/JUnit,
canonical `.atto` guest execution and process-stack survival **NOT_EXECUTED**.
Stacked on [Java #2642](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2642).

## Compiler/runtime work

The actual `OresEvalRootNode.Evaluator.Env` now binds supported source local
scalar declarations, initialization, reads and `let` reassignments through
`OresScheduler` into the owning `ActorSourceTaskFrame`. Eligible local
slot values are **read back from the actor arena**, not the JVM `Slot.value`
shadow, even after `await`/`cooperate`. A lexical slot is identified
by a monotonic scope epoch plus its name; a snapshot alias retains the
originating epoch. Any attempt to read or change a managed slot outside
the physically authenticated actor SourceTask turn is rejected.

This is a **bounded interpreter lowering**: the existing 64-byte actor task
frame is 4 bytes of parameter-header plus at most six 9-byte tagged scalar
slots shared between scalar parameters and lexical locals. Formal scalar
parameters already stored by M3.1 are not allocated twice. Local slots are
assigned stable first-bind offsets, persist across suspension, and are only
reclaimed with the owning SourceTask after physical quiescence. No extra frame
is pushed on the suspended execution and no second byte charge is made.
New local values that are references/closures, or locals that exceed
remaining capacity, stay explicitly on the existing JVM Env path. Once
bound, an owned scalar local cannot silently fall back: a primitive-type
change, stale slot or mismatched owner poisons the actor generation.
Unsupported guest pointers are never encoded.

**Important limitations:** The old JVM `Slot.value` is still updated as
a compatibility shadow. Thus this is NOT exclusive off-heap source storage
or a proof that all guest variables are inaccessible to the JVM. Scalar
slots are not currently reclaimed when a nested lexical scope ends, so
subsequent locals beyond six fall back to the JVM. Arbitrary object-valued
bindings, structured roots, multiple activation frames, operand stacks,
return PCs, exception unwind and nested expression recursion remain on
JVM objects or native carrier stacks. A closure using an actor-bound scalar
slot after its owning SourceTask retires must be rejected, not silently
rehydrated from the JVM shadow.

## Explicit call/return ABI precursor

`ActorGuestReturnRecord` introduces a **48-byte actor-owned record
format** for a future iterative interpreter:

- 32-bit ATTO magic and ABI version
- 32-bit callee index, call-site PC, return PC and operand depth
- 64-bit actor generation, owner and unique logical execution number

Entry allocates a generation-fenced logical execution context and charges
one actor frame. Read/close validate the owning physical turn and capability;
close zeroizes and releases the exact frame. Invalid layout or authority
fails closed. There are **no Java return addresses or object pointers**.

This record is **NOT YET WIRED INTO ACTUAL GUEST DISPATCH**. The existing
source evaluator still calls `executeRaw` recursively on non-tail guest
calls. The new ABI alone does not enforce correct return control flow,
reduce JVM recursion or establish process-wide overflow containment.
The next compiler stage must build iterative dispatch from typed HIR/MIR
with verified call-site and return-PC instructions.

## Tests authored — NOT_EXECUTED

- `ActorSourceTaskLexicalFrameTest`: six JUnit host/runtime tests:
  actor-owned scalar rebind across await; six-slot aggregate quota with
  typed parameters; same-name/different-epoch isolation; foreign carrier
  denial; fail-stop type mutation with sibling survival; cancellation
  reclaim and stale await rejection.
- `ActorGuestReturnRecordTest`: four host/runtime JUnit tests:
  exact 48-byte cursor readback/zeroization, foreign carrier denial,
  actor-local 64-frame budget with independent sibling survival, and
  malformed negative cursor rejection.
- `ActorLexicalScalarFrameLanguageTest`: one authored canonical
  `.atto` compiler/evaluator test
  `lexical-actor-frame-cooperate.atto`, expecting
  `before|42|second`. It reassigns a `let int` local across
  **two** `rt cooperate` boundaries while another user message waits.

Required admitted exact-head Java 21+/GraalVM Maven command:

```sh
mvn -B -ntp -Dtest=ActorSourceTaskLexicalFrameTest,ActorGuestReturnRecordTest,ActorLexicalScalarFrameLanguageTest,SourceTaskScalarParameterBindingTest,ActorSourceScalarParameterLanguageTest,ActorScalarSourceTaskIntegrationTest,ActorSourceTaskFrameTest,GuestScalarCallFrameTest,GuestInvocationFrameTest,ActorFrameArenaTest,ActorGuestStackTest,GuestCallDepthGuardTest,GuestCallDepthLanguageTest,ProperTailCallTest test
```

Capture exact commit SHA and Git tree, toolchain versions, JUnit XML,
compiled source stdout, runner ID and nonzero job steps, negative
mutation results and independent reviewer approval. Zero-step/null-step
jobs are runner-admission failures, not test evidence. No backend
may claim guest actor-stack parity or native process overflow survival.

## Next: real stackless guest dispatcher (M3.3)

1. Lower every supported call into explicit instructions and actor-owned
   activation frames with typed operands, saved return-PC, receiver,
   cleanups and ownership roots; no unbounded recursive JVM guest call.
2. Prove real 100k proper tail recursion and bounded non-tail
   recursive actor failure BEFORE a native stack guard page.
3. Convert source `await`/cancel/STOP into state-machine transitions
   retaining these activation frames, generation-fenced through migration.
4. Compile actor A stack failure and sibling B progress on reused carrier,
   supervisor CONTROL error/restart and stale continuation denial.
5. Execute exact-head full guest/native tests across Java/GraalVM, LLVM,
   .NET NativeAOT, Wasm and Forge/Cranelift.

Do not merge this or parent drafts without independent review and
executed exact-head CI. No physical per-actor stack safety is claimed.
