# Repeating sequence positional type invariants

This is a Java/Graal compiler hardening candidate dated 2026-10-19; it does **not** assert LLVM guest-source parity.

- A runtime-extent type `Array[...(3 of int, 3 of string, 3 of bool)]` retains a repeating unit of nine statically typed slots, distinct from the ordinary union-valued `Array[int | string | bool]`.
- The `Types.ListType` static representation retains both the joined read element type and an immutable repeating unit. It introduces no runtime reflection, copying, or physical-layout promises.
- Patterned and unpatterned lists are invariant under implicit assignment: a union alias must not acquire a positional proof, and an alias allowing arbitrary mutation must not invalidate an existing proof.
- Declared literal initializers and returned list literals are admitted only after complete arity and per-position checking, with zero complete repetitions allowed. Type substitutions and receiver binding preserve shape metadata. Readonly and actor/channel/sendability checks cover both the joined element and each repeated slot. The static representation validates each slot against its joined element type.
- `add`, `set`, and indexed writes to patterned lists currently fail closed until shape-preserving operations and borrowing/capability proofs are integrated. Rest destructuring fails closed until its view/copy ownership semantics are proven. Unpatterned homogeneous lists retain existing mutation behavior. `get` conservatively returns the union read type.
- Repeating patterns with `rank>1` fail closed pending a precise multidimensional contract. The shorthand bare `[...(...)]` is not enabled by this change.

**Verification status:** execute `mvn -q -Dtest=RepeatingSequenceShapeSafetyTest,CollectionShapeMetadataTest,NativeCollectionMemberRuntimeTest test` at the exact proposed commit. This change still needs an audit of nonliteral constructors, casts, generic APIs, FFI, other mutation mechanisms, and full actor boundary handling before promoting the type invariant to unconditional backend parity.

The LLVM main frontend currently implements a narrower typed-i64 source subset; its native array ABI is not evidence of complete heterogeneous sequence-pattern parsing or lowering. Source mirroring to another private org must use independently approved and pinned source-tree provenance; this PR does not publish source or use cross-org mirrors.

## Additional audit hardening (2026-10-20)

- Runtime type checks and casts inspect **transitively expanded type aliases**. A named alias to a non-runtime-reifiable `Array[...(T1, T2)]` cannot bypass the structural type-test restriction. Alias cycles fail closed; primitive aliases still work.
- A built-in constructor for a patterned runtime-extent array is accepted only with zero constructor arguments. Constructors that might create a nonempty but incompletely typed sequence are rejected before granting pattern metadata. Full-group literal initialization remains permitted.
- A typed repeating group cannot obtain its static slot proof from an expression containing `Unknown`; both the contextual and declared-literal validators reject unresolved slot types. `ListType` metadata also rejects `Unknown` joined elements or slots while preserving ordinary compile-time `Generic<T>` slot support.
- The additional JUnit tests assert that the parser accepts alias-cast and constructor fixtures before requiring the type checker to reject them; merely failing to parse is not evidence of semantic safety.
- Exact-source carrier review is tracked by [Java issue #1699](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1699) and [source-free carrier plan PR #17](https://github.com/opto-sync-test/go-java-validation/pull/17). Neither review artifact authorizes private source transfer.

## Generic-inference hardening and execution boundary

- Generic parameters within a repeated group infer from each corresponding **ordered slot**, not the joined union value type. For example `Array[...(T, string, bool)]` can infer `T=int` from a proved `Array[...(int, string, bool)]` argument. A nonmatching slot causes a type-check failure.
- Runtime-extent shape proof metadata cannot substitute unrelated generic placeholders for the same slot or joined type. Exact generic identity is required in the join; an unconstrained generic value by itself does not prove a shaped array.
- This patch now has **13 JUnit test methods**, but no test has been shown to execute: GitHub Actions failed before runner steps on the exact source branch. Source-level inspection is not compilation or execution.
- A separate **public** GitHub organization (`flags-2-env/flags-2-env`) had successful Actions jobs on 2026-10-20, but it is not approved or private and must **never** receive Oreslang private source.
- The candidate private carrier test-plan [PR #17](https://github.com/opto-sync-test/go-java-validation/pull/17) and source security [issue #1699](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1699) remain review-only; an actual code mirror must not be created before authorized protected merge, provenance checks and independently observed runner capacity.
