# Java / Graal compile-time STATIC_WITNESS specialization planner

This change implements **a bounded, deterministic compiler helper** on top
of the existing coherent `TypeclassRegistry.Registry.resolveWithBindings()`
frontend primitive. The helper does not require or perform JIT compilation,
runtime registry lookup or guest code execution.

## Semantics

For a fully ground typeclass application and checked callable symbol,
`StaticWitnessAotPlan.Builder.add()`:

- Resolve exactly one coherent witness at **compile time**; reject missing,
  ambiguous or open/variable types. Preserve ground type substitutions.
- Construct a deterministic, collision-resistant target identifier from the
  *length-prefixed* typeclass, callable, dictionary and fully qualified
  type argument identities, hashed using SHA-256. Different generic type
  applications cannot depend on source order, map insertion order, or
  ambiguous raw `module::name` delimiters.
- Deduplicate repeated applications, cap reachable specializations at
  **4096**, and cap type argument graph traversal at 1024 nodes and 48 deep.
- Reject distinct witnesses attempting to share a dictionary ABI symbol
  within one plan. All error paths must leave the admitted plan unchanged.
- Return an immutable, deterministic snapshot for a **future backend
  linker/codegen pass**. No witness table is allocated in a guest runtime.

## Executable evidence

`tools/ci/StaticWitnessAotProbe.java` exercises actual Java compiler-helper
classes (not a mock resolver) under the JDK. Its positives include ground
typeclass resolution, deduplication, determinism regardless of source/import
order and a full 4096-instantiation bounded plan. Negatives cover open
variables, wrong arity, missing witness, unsafe callable identifiers,
dictionary ABI collisions, overly deep types and budget exceedance.

To run without GraalVM/Maven dependencies:

```sh
mkdir -p build-witness-proof
javac -d build-witness-proof \
  src/main/java/dev/oreslang/types/TypeclassRegistry.java \
  src/main/java/dev/oreslang/compiler/StaticWitnessAotPlan.java \
  tools/ci/StaticWitnessAotProbe.java
java -cp build-witness-proof StaticWitnessAotProbe
```

The GitHub workflow also requires exact source head/tree, nonzero executed
steps, and compiler-input Git object identities. `steps=[]` means runner
admission failure; it is not evidence that Java compilation ran.

## Important remaining implementation work

This is **not** general Java/GraalVM devirtualization, a typed HIR/MIR
specialization pass, emitted direct machine-code functions, or a Graal Native
Image executable. The generated `targetSymbol` is a *planned* symbol, not
evidence that a backend emitted or linked it. Production use requires:

1. Bind the helper only from the semantic pass after hygienic macro expansion
   and successful **type, borrow, effects, capability and visibility** checks.
2. Produce actual monomorphized methods/dictionary witness wrappers and
   check linker output contains every target, with no dynamic fallback.
3. Include inherited override proofs for class methods separately from
   typeclass witness selection; a declared base receiver alone never proves
   a unique method target.
4. Execute a real Graal Native Image `.atto` guest with JIT disabled and
   compare exact behavior against LLVM, .NET, Wasm and Forge, including
   negative/no-artifact tests and code-size/compilation-budget checks.

Shared category semantics and budget ceilings are staged in
[common-source #265](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/265).
This helper is a first code-level building block, **not** backend conformance.
