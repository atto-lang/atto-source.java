# M3.3c — independent scalar bytecode CFG/type verifier

**Scope:** restricted actor-owned scalar `fnc` call/return machine only. This
is a stacked child of [Java #2668](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2668).
No change to the parent PR head, existing source semantics, private runner
policy, or cross-backend parity claim.

## Why an independent verifier is needed

The M3.3a/b `ActorScalarSourceLowering` estimates operand depth as it emits
instructions, and `Function` checks individual local and branch indexes.
Neither constitutes a **control-flow proof**: jumps can join at different
stack depths or primitive kinds, a local can be initialized on one branch
but not another, and a hand-constructed immutable Program can bypass source
AST lowering. These conditions must fail **before** an actor generation or
frame quota is touched, not at the first guest read after a corrupt CALL.

`ActorScalarBytecodeVerifier.verify` is now invoked by the immutable
`ActorScalarCallMachine.Program` constructor after validating all static
call target indexes. It analyzes each function independently using a
bounded forward worklist over reachable PCs. Every PC state includes:

- Exact stack kind sequence (`INT` or `BOOL`), not just depth.
- A definite-initialization bitset for its local slots, initially set for
  exactly the function parameters.
- Control-flow merge: operand kinds and depths must match **exactly**;
  initialized-local state is the intersection of all incoming paths.
- Loop backedges are revisited until definite-assignment bits converge,
  with a finite explicit analysis budget (no recursive Java verifier walk).

Each opcode has a checked type-and-stack transfer: arithmetic needs
two INTs, comparisons produce BOOL, equality compares identical primitive
kinds, conditional jumps consume BOOL, STORE consumes INT and initializes
its slot, LOAD requires a definitely initialized slot, CALL/TAIL_CALL
consume exactly the callee's INT parameter count, CALL produces one INT,
TAIL_CALL must leave no caller operands, RET consumes exactly one INT and
must leave the stack empty, and TRAP cannot discard live operands.
Reachable instruction paths cannot fall off the image or exceed
`Function.maxStack()`.

A separate full-image pass also rejects **noncanonical instruction fields**
even when the instruction is unreachable (e.g. an ignored argument on ADD,
an unused payload on CALL, or a decorated TRAP). Thus no future optimizer
can silently make formerly unused encoding bits meaningful. No dynamic
jump targets, function references, object roots, closures, or implicit
conversions are introduced.

`ActorScalarSourceLowering.tryCompile` catches the constructor's
`IllegalArgumentException` from the independent verifier and performs a
**whole-call-graph fallback** to the existing interpreter. This is a
conservative compiler-subset decision, not permission to execute source
that the language's own static checker rejects. Direct untrusted bytecode
`Program` construction still throws an explicit invalid-image error.

## Existing test expectation repairs

Five preexisting host tests formerly constructed deliberately malformed
bytecode and expected to observe actor poisoning **during execution**:
operand underflow, Boolean INT return, Boolean arithmetic, mixed-kind
equality and Boolean INT-callee argument. Those now correctly assert
`IllegalArgumentException` from immutable Program admission. Runtime
frame-byte corruption tests (forged PC/callee/SP after successful
verification) remain actor-local fail-stop tests, as they should.

## New tests and actual evidence

`ActorScalarBytecodeVerifierTest.java` adds **39 authored JUnit methods**.
They cover both/one-sided definite assignment, loops and backedges,
different stack types/depth at branch joins, correct and bad return types,
missing/extra operands, reachable fallthrough, unreachable instructions,
max stack, typed equality/arithmetic/conditions, direct and tail-call
arguments and results, no discarding live tail-call operands, canonical
field encodings, and valid traps. The *stacked* M3.3a/b/c suite now contains
38 actor-machine host, 32 parser/AST, five compiled `.atto`, plus 39 new
verifier methods: **114 authored Java JUnit methods, zero hosted JUnit
executed** pending runner admission.

**Locally executed narrow receipt:** Using Java 21 and stubbed
`ActorScalarCallMachine` immutable Function/Program records (but the
**exact committed byte-for-byte `ActorScalarBytecodeVerifier.java`**
source), an offline standalone Java harness compiled and ran **40/40**
verifier assertions. The local `git hash-object` of the compiled verifier
source was `0cd147ac955aaa08eb4346a2d342025123102331`, exactly
matching the GitHub source blob. This is **NOT** Maven, JUnit, actual
AttoLang parser, GraalVM compiled guest or hosted GitHub Actions
execution. The synthetic harness does not replace the authored 39
JUnit tests, which remain unexecuted. It provides only narrow Java
algorithm-level evidence.

Required exact-head hosted execution, after private Actions runner
admission has been diagnosed and restored:

```sh
mvn -B -ntp -Dtest=ActorScalarBytecodeVerifierTest,ActorScalarCallMachineTest,ActorScalarSourceLoweringTest,ActorScalarIterativeLanguageTest,ActorLexicalEnvSnapshotExecutionFenceTest,ActorLexicalTaskCaptureFenceTest,GuestCallDepthGuardTest,ProperTailCallTest test
```

Record actual PR HEAD/tree, JDK/GraalVM version, runner ID/attempt,
nonempty steps, JUnit XML, canonical `.atto` source fixture bytes and
expected stdout. Prior private-hosted Jobs have reported
`runner_id=null`, `steps=null` and no retrievable logs: this is
**RUNNER_ADMISSION_FAILURE**, not Java compiler/test evidence.
Do not mirror private source to a different repository without
independent security approval and exact-tree attestation.

## Still not established

M3.3c proves only a restricted pointer-free scalar bytecode image,
not actor-owned reference roots, method/closure calls, async
suspension/return-PC lowering, exception/defer cleanup, supervisor
restart generation fencing, native small-stack survival or JVM
carrier stack elimination for the **legacy fallback**. Normal source
dynamic behavior continues on the legacy evaluator where unsupported.
No five-backend compiler guest parity or physical stack-isolation
certificate should be claimed until executed exact-head evidence exists.
