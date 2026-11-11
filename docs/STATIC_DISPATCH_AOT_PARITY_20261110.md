# Java/GraalVM static-dispatch parity handoff

**Status: semantic regression tests staged; executable JVM test receipt pending. Native Image direct-call AOT proof not yet established.**

Shared target contract: `oreslang-common-source/docs/STATIC_DISPATCH_AOT_V1.md` (PR #265).

Java currently selects class-method slots using owner, member kind and explicit argument arity. Overriding keeps the same slot identity. A statically known declared base type does **not** prove a unique runtime method target in an extensible hierarchy. Existing `ArityOverloadAotTest` and `MethodOverloadTest` already exercise many of these obligations.

This branch adds `StaticDispatchAotBoundaryTest` for four checks:
1. Concrete receiver calls resolve distinct overloads; a child override changes the selected implementation, while an inherited zero-arity implementation remains available.
2. Two same-name/same-arity declarations do not become distinct solely because parameter types differ.
3. Unrelated inherited implementations with the same slot cannot silently resolve by parent order.
4. A reachable child override makes the *declared* base-class method insufficient as a devirtualization proof.

The exact-head workflow runs real JVM parser, type checker and guest execution with JDK 25. This is **not** a JIT-off test: the controlled JVM guest profile permits JIT. No measured dispatch speedup, produced Graal Native Image, executed standalone artifact or five-backend parity is claimed.

## Next compiler integration

- Emit typed call-site dispatch evidence after macro expansion and semantic checking: receiver identity, method slot, effect/borrow/visibility/capability proof, and the complete reachable override set.
- Convert only provably exact/final or sealed-unique class calls to direct targets. Keep correct precompiled fallback for any remaining open polymorphism.
- Generate static generic/typeclass witnesses and finite specialization closure; reject an unbounded graph before Native Image.
- Build a real Graal Native Image with no guest runtime compiler, run supported `.atto` dispatch fixtures, and compare to JVM and LLVM oracle results.
- Require exact-head executed tests and an independent review before merge.

Until the compiler emits proofs and the native image executes, this remains a semantic admission enhancement, not an AOT devirtualization implementation.
