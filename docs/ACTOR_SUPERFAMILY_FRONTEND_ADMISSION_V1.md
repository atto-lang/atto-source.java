# AttoLang abstract actor superclass parser v1

**DRAFT; parser/AST only; NOT compiled guest execution.**

The Java frontend admits `define abstract actor Name<T> as ... end` with
signature-only methods, and one nominal actor parent declared with
`from Parent<T>` (or legacy `extends`) while preserving the
`Ast.ClassDecl.isAbstract` flag. This is a preparatory parser stage, not
actor-inheritance semantics or a new keyword.

- Abstract actor parents cannot own mutable actor fields or state schemas.
- Concrete actors cannot declare abstract/signature-only methods.
- Multiple parent types are rejected.
- Transport-specific abstract actors are rejected.
- The pre-existing explicit source rejection of `state { ... }` remains,
  because compiler-sealed borrow checking and actor state ownership have not
  been installed.
- The `pure` reducer modifier and full source `EventSourcedActor` in
  [stdlib draft #2763](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2763)
  are **not yet executable**. Do not delete `pure` or downgrade it to
  unverified ordinary callable semantics to gain superficial parser passes.

## Critical work before positive runtime admission

1. Resolve parent types nominally, reject ordinary class->actor and actor-kind
   escalation, validate generic instantiation, method overrides and abstract
   obligations at compiler/link stage.
2. Implement transitive pure/effect-free `decide`/`apply_event`, including
   deterministic replay requirements and proper `Result` error propagation.
3. Implement exactly one sealed concrete actor `state { ... }`, with
   `handle_state(critical-read/critical-write)`, no escaping loan, and no loan
   across suspension. Prevent duplicate inheritance of state schemas.
4. Lower compiler-generated staged immutable event -> trusted provider commit
   -> verified receipt -> state publication under actor execution lease.
5. Run exact-head JUnit parser tests, real source compile, actor runtime and
   cross-backend Java/LLVM/.NET/Wasm/Forge fixtures. A zero-step CI job is
   NOT execution evidence.

This parser feature MUST NOT be merged as proof that the stdlib super-actors
can yet persist or recover guest state.

## Type-checker ancestry gate (second slice)

`TypeChecker.checkClassBody` now recursively validates the entire nominal
actor ancestor chain, not only the direct parent. A parent or grandparent with
class fields or an explicit constructor is rejected, as is a parent that is
an ordinary class rather than an actor. This is necessary to keep future
compiler-sealed `state { }` the **only** mutable state region of a leaf actor.

Focused negative JUnit cases cover a fieldful actor parent, a fieldful
grandparent, ordinary-class actor escalation, missing nominal parent and
inheritance cycles. They require actual execution of the exact-head Maven
workflow; repository source inspection does not constitute a passing test.

The runtime still cannot execute a `pure` event reducer or a sealed state
block. Neither parser/TypeChecker AST acceptance nor an abstract actor
superclass provides durable access without verified guest lowering.
