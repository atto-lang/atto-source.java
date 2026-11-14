# Optional and named parameter main integration — 2026-11-14

**Status: parser/AST + fail-closed compiler entrypoints; NOT executable omission.**

This main-based integration keeps the two question-mark positions orthogonal:

- `int? required`: a **required** parameter with nominal type `Option<int>`.
- `int retries? = 3`: an omittable formal with retained default AST, not yet lowered.
- `int? maybe?`: an omittable `Option<int>` with no explicit default, provisionally typed `None` when future lowering is admitted.
- `Option<int>? wrapped`: a required `Option<Option<int>>`.
- The parser does not turn `None` into omission, silently wrap plain `T` into `Some(T)`, or allow `T x?` without a default when `T` is not Option.

Call-site omission, checked constant-default evaluation, generic/inherited/constructor ABI slot binding, source-order named evaluations, dynamic/indirect calls and native parity are **not yet executable**. Named value and generic calls belong to Java integration draft #2612 and MUST be reconciled with this main-based stage, not copied wholesale over newer main compiler changes. Parameter presence draft #1519 and older binder draft #1335 are references, not green CI evidence.

`UnloweredParameterPresenceGuard` rejects name-level omissions/defaults in the normal and linked typechecker, direct ownership checker, and Truffle execution root. Parsed lambdas reject omission; a forged AST lambda is rejected by type and ownership checks. Structural annotation normalization preserves both question-mark bits and the default AST.

The JUnit source tests assert parser metadata, nested Option types, illegal defaults and direct/linked preflight denial. They are **authored, not tested on a stepful JDK 25 build**. Source-org GitHub Actions frequently fail with no runner job steps; that failure must not be called a source compilation failure or a green pass. No private compiler code is mirrored without a reviewed authorization.

To enable omission safely, implement one checked plan that preserves source-call-item and evaluated-expression indices independently of formal slots, evaluates supplied expressions exactly once in source order, admits deterministic typed defaults only when absent, guards overload interval ambiguity, and binds fully declared ABI arity in JVM, Graal Native Image, LLVM and .NET Native AOT. Then execute the canonical .atto positive and rejection fixtures in attolang-language-spec.md PR #51 on real runners.
