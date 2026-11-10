# AttoLang M3 — typed scalar fnc frames and iterative Java dispatch

**Status:** draft implementation, no Maven/hosted compiler-backed execution
receipt on this exact Git head. No claim of full actor stack isolation.
Stacked on integrated Java PR #2637 (and its #2631/#2628/#2627
prerequisites). Public language name: AttoLang. Repository/package paths
remain legacy Oreslang while compatibility migration is in progress.

## Implemented compiler slice

The common `OresEvalRootNode.Evaluator.invoke` path now attempts an
**all-or-nothing, before-execution** compile for a very small pure scalar
`fnc` graph. It never falls back to the legacy evaluator after running an
instruction. Unsupported source is left untouched and executes through the
old path. The plan accepts:

- `fnc` only, `int` parameters and return, no annotations, generics,
  checked effects, generator, trap, async/suspends, actor declaration,
  or runtime imports/host-symbol resolution;
- immutable integer parameters/bindings, integer and boolean literals,
  direct name loads, `+ - *`, numeric comparisons and equality,
  unary +/-/!, strict if/else and explicit returns;
- same-source-module direct calls to other fully admitted scalar `fnc`
  declarations. Mutual recursion is admitted; direct and mutual tail
  returns become explicit TAIL_CALL instructions.

Every reachable function must be compilable before *any* guest instruction
executes. A graph over 32 declarations, AST recursion over 64 levels, per
function over 4096 instructions, or more than 64 operand cells is rejected
for this fast path and falls back BEFORE execution. The existing frontend's
type, ownership and effect checks remain authoritative; the new compiler
does not introduce conversions or ownership syntax.

`ScalarFrameMachine` compiles explicit typed PC/locals/operand/branch/
CALL/TAIL_CALL/RETURN instructions. The runtime uses one iterative Java
dispatch loop. Non-tail calls push a new scalar frame; tail returns
replace the active frame instead of accumulating recursive carrier
stack depth. A scheduler safepoint fires at least every 64 scalar
instructions.

`ScalarFrameStorage` owns per-call frames. Each frame has:
- 8-byte PC and 8-byte operand height header;
- 16-byte scalar slots (type tag and 64-bit payload) for parameters,
  locals and operand values;
- LIFO, single-active-top access; byte-for-byte zeroization on pop;
- failstop actor poisoning on caught stale leases and corrupted frame tags;
- actor-generation and logical execution identity fencing by
  `ActorFrameArena`, with the current SourceTask execution used where
  available. No guest pointers, closures or host objects are stored in
  primitive slots.

For actor calls, *actual scalar local/operand values* now reside in the
actor-owned managed byte arena, not merely in a reserved placeholder
descriptor. The metadata `Code` reference and VM activation list remain
trusted host-side objects that exist only until the synchronous call
returns. For root/nonactor calls, the same typed byte layout uses a
bounded 2,048-frame local store. Actor calls retain the existing 64-frame,
64-KiB per-incarnation arena quotas (also shared with task roots and legacy
guarded calls). A source task root consumes one frame quota slot.
No raw JVM/native stack switching is attempted.

## Evidence authored (NOT executed)

- Seven `ScalarFrameMachineTest` direct AST→scalar IR→VM JUnit tests:
  1,024 non-tail recursive calls; 100,000 proper tail transfers and
  safepoints; 100,000 mutual-tail transfers; immutable local/branch;
  bounded root-depth negative; unsupported routine/defer/dynamic
  call/nonprimitive/ownership mutation rejection; mutable/shadowed
  binding rejection.
- Five `ScalarFrameStorageTest` host/runtime JUnit tests: typed
  slot/PC/LIFO/stale handles; type-tag negatives; actor byte charging
  and zeroization; quota-overflow actor failure, caught-stale lease
  poisoning with sibling survival
  on a shared one-worker carrier.
- Three `ScalarFrameLanguageTest` true .atto frontend/evaluator
  acceptance tests with source files:
  `deep-non-tail-scalar-frame.atto` (expect 1024);
  `mutual-tail-scalar-frame.atto` (expect 0);
  `immutable-local-scalar-frame.atto` (expect 9).

Source fixture presence, compiler implementation and Java unit-test
authorship are NOT successful compiler-backed guest receipts. Native
stack survival is unverified until exact-head executed tests and
process output exist.

## Exact-head source validation

After runner admission is repaired, obtain this PR's **full** head SHA
and tree SHA from GitHub, then checkout its exact head and run:

    EXPECTED_HEAD=<reviewed-full-head-sha> EXPECTED_TREE=<reviewed-full-tree-sha> bash scripts/verify-scalar-frame-m3.sh

The script rejects mismatched Git identity, missing source fixtures,
Maven test failures, missing Surefire XML, missing required JUnit
classes, and zero executed methods. It records Java/Maven version and
canonical fixture Git blob IDs. Capture the hosted workflow run/job
IDs, nonempty executed steps, review approval, JUnit XML, guest
stdout/process exit, and negative mutation receipts separately.
`steps=[]` or `steps=null` is a runner-admission failure, never a
successful test. Source-free canaries cannot satisfy guest execution.

## Explicit non-goals / remaining isolation gaps

- Methods, constructors, closures, expression shapes beyond the strict
  scalar subset, noninteger locals, async functions and structured
  cleanup still use the existing evaluator. A mixed program is NOT
  globally stackless: only completely admitted call graphs use the
  scalar VM.
- SourceTask continuations are not yet lowered into typed persistent
  suspended PC/locals; only synchronous scalar activations are represented.
- Native JVM carrier stacks, JNI/FFI, legacy recursive expression walkers,
  and Truffle-hosted operations are not made independently killable
  by a managed actor frame arena. Never recover from StackOverflowError
  or native stack faults by catching them.
- A compiled actor A failure / actor B survival / supervisor restart
  generation-fence test with low-native-stack JVM and hosted exact-head
  receipts is still required.
- LLVM, .NET NativeAOT, Wasm and Forge/Cranelift are NOT_EXECUTED on
  the portable typed frame contract.

Promotion gates: executed CI, full guest receipts, mutation negatives,
independent reviewer and backend-specific proof. This PR stays DRAFT
and unmerged until those gates are met.
