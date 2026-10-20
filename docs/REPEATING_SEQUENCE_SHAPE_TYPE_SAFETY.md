# Repeating sequence positional type invariants

This is a Java/Graal compiler hardening candidate dated 2026-10-19; it does **not** assert LLVM guest-source parity.

- A runtime-extent type `Array[...(3 of int, 3 of string, 3 of bool)]` retains a repeating unit of nine statically typed slots, distinct from the ordinary union-valued `Array[int | string | bool]`.
- The `Types.ListType` static representation retains both the joined read element type and an immutable repeating unit. It introduces no runtime reflection, copying, or physical-layout promises.
- Patterned and unpatterned lists are invariant under implicit assignment: a union alias must not acquire a positional proof, and an alias allowing arbitrary mutation must not invalidate an existing proof.
- Declared literal initializers and returned list literals are admitted only after complete arity and per-position checking, with zero complete repetitions allowed. Type substitutions and receiver binding preserve shape metadata.
- `add`, `set`, and indexed writes to patterned lists currently fail closed until shape-preserving operations and borrowing/capability proofs are integrated. Unpatterned homogeneous lists retain existing mutation behavior. `get` conservatively returns the union read type.
- Repeating patterns with `rank>1` fail closed pending a precise multidimensional contract. The shorthand bare `[...(...)]` is not enabled by this change.

**Verification status:** execute `mvn -q -Dtest=RepeatingSequenceShapeSafetyTest,CollectionShapeMetadataTest,NativeCollectionMemberRuntimeTest test` at the exact proposed commit. This change still needs an audit of nonliteral constructors, casts, generic APIs, FFI, other mutation mechanisms, and full actor boundary handling before promoting the type invariant to unconditional backend parity.

The LLVM main frontend currently implements a narrower typed-i64 source subset; its native array ABI is not evidence of complete heterogeneous sequence-pattern parsing or lowering. Source mirroring to another private org must use independently approved and pinned source-tree provenance; this PR does not publish source or use cross-org mirrors.
