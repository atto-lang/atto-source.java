# Cross-file actor receiver method binding (Java static admission)

This corpus covers the requested two-file `namespace foo;` example.

- `file1.atto`: nominal `Bar` with its `make_call` method and external receiver `do_x`.
- `file2.atto`: external receiver `do_y`, calling `do_x()` and `b.do_x()`.

The parser accepts the canonical receiver-first external method form
`define mtd (Bar b) do_y(): void { ... }`, retaining the staged older
`define mtd do_y(Bar b)(): void { ... }` syntax as a compatible alias.

`ActorReceiverBinder.bindSourceSet(packageIdentity, sourceUnits)` provides a
**source-only static nominal binding**. It sees actor and external method
declarations with the same explicit namespace inside a caller-supplied
source map. The output keys include package identity, namespace, nominal
actor name, method name and arity. Each binding retains both source-unit
provenances. Missing/duplicate actor targets, duplicate slots, in-class
collisions, ordinary-class impostors, cross-namespace leakage, public
method exposure of private actors and implicit named-module coalescing
fail closed.

A non-public actor is allowed to acquire private external methods from
other files in the exact same namespace. This means compiler-owned method
attachment only; it **does not** permit arbitrary same-namespace code to
call private methods, grant `ActorContext`, borrow actor state or dispatch
a mailbox turn.

## Important limits

- Static slot identity does not yet rewrite the unqualified `do_x()`
  or explicit `b.do_x()` call expressions. The test corpus preserves
  those exact forms for the later typed-call and executable dispatch gate.
- The caller-provided source map is **not** verified package membership.
  Wire this to the independently validated `PackageNamespaceBuild`
  from PR #2813 before treating cross-file lookup as production-capable.
- Existing `TypeChecker` intentionally rejects external `mtd`
  execution until actor-owned code context, nonreentrant same-actor calls,
  external ActorRef mailbox semantics, cancellation and restart-generation
  fencing are implemented.
- Actor `receive` is supplied with an executable method body in the
  fixture because the exact signature-only form in the user example
  requires a separate concrete/abstract actor-method admission decision.
- No Java/Graal guest, LLVM, .NET, Wasm, Forge or NativeAOT execution is
  claimed by the existence of these source files/tests.

Run `CrossFileActorReceiverBindingTest`, `ActorReceiverBinderTest` and
`ExternalActorReceiverAstAdmissionTest` on an admitted runner at the
exact PR head. Record the root Git tree, nonzero executed steps,
JUnit results, and later real guest call/return receipts.


## Follow-up hardening

The closed-source-map binder now validates every namespace group, even one
without external methods, so duplicate actor/type declarations cannot hide
until an unrelated `mtd` is added. Its file identifiers must be normalized,
relative, slash-delimited `.atto` (or legacy `.ores`) paths. It rejects
parent traversal, backslashes, empty/dot segments, control characters and
case-insensitive path collisions, while allowing harmless embedded `..` in
filenames. A single AST node may not be presented as originating in two files.

Memory/workload bounds for this static API are 4096 source units and 65536
file-root declarations. In-class actor method slots are indexed once by
`(methodName, valueArity)` instead of scanning all embedded methods for
each external method; static methods remain a separate dispatch domain.
A duplicate embedded instance slot is an error. These changes are
compiler-side metadata checks and do not prove guest execution.

`CrossFileActorReceiverBindingTest` now has **24 authored JUnit test methods**
for the positive two-file source and negative/edge cases. The focused
`.github/workflows/cross-file-actor-mtd-junit.yml` workflow pins the exact
source head, reports the Git tree and fixture hashes and requires real
non-skipped Surefire reports for all three actor binder/parser suites.

**Evidence remains NOT EXECUTED** until a runner executes the exact latest
head. Zero-step GitHub Actions failures are runner-admission failures, not
compiler or JUnit results. Do not advance the PR to merge-ready based on
static source readback or source-free canaries alone.
