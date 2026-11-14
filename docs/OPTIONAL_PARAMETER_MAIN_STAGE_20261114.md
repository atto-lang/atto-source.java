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

## Reused checked argument binder (staged)

The main-compatible `CheckedCallPlan` from the older Java named-argument draft
is now available as a **shape-only** reusable binder. It binds canonical
`Ast.Param.omittable()` metadata, retains lexical input indices separately
from formal ABI slots, and never treats a required `T?` as omittable.

The binder checks missing required parameters, duplicate/unknown labels,
nontrailing omission, prohibited positional-after-named ordering, source
provenance including dense raw call-item indices, and spread projections.
Identity-bound call plans can be invalidated on recheck; the AST metadata
transfer policy recognizes them while refusing unsafe rewrites. An 8,192-case
JUnit exhaustive matrix is authored. This is **not** compiler call lowering,
real named invocation or passing CI evidence.

## Initial TypeChecker integration

The existing Java TypeChecker now attaches the checked shape plan to direct,
fully supplied positional calls to statically resolved top-level and qualified
module functions, **only after the call has passed type checking**. It does
not authorize spread, named, dynamic, method, partial or defaulted calls.
The TypeChecker's ordinary and linked entrypoints use a validation scope to
revoke plans attached during a subsequently failing declaration check.
Tests verify successful type-check publication and failed-pass rollback.
This step does not change executable argument evaluation or arity.

## Name-first parity (2026-11-14)

AttoLang now *parses* both declaration orders to exactly the same `Ast.Param`:

```atto
fnc calculate(int x, int y? = 10, int? z?): int { return x + y; }
fnc calculate(x: int, y?: int = 10, z?: int?): int { return x + y; }
```

The second spelling's omission marker goes **before `:`** and the type
marker stays **after the type**. `z: int?` is still a *required* Option
value; `z?: int?` is omittable and defaults to typed None. Name-first
`x: int??` is rejected rather than interpreting the second type suffix as
an omission flag. This normalization is scoped to formal lists, including
class methods, interfaces, declarations using `|...|`, and lambdas. 
Call-site named arguments, checked defaults and executable omissions are
**not enabled by this parser change**; their guards remain active. The JUnit
cases are authored but not an executed compiler/guest receipt.

## First named-value executable profile: required `int` only

The Java source compiler now preserves each direct call's argument label
alongside the corresponding **lexical** argument expression. The TypeChecker
accepts a deliberately narrow initial profile: statically resolved,
non-generic, non-suspending, non-trapped, non-actor, full-arity free functions
whose formals and return type are all plain `int`. A checked immutable
`CheckedCallPlan` maps each lexical source value to its ABI slot.
The OwnershipChecker independently reconstructs this plan and verifies each
source value exactly once in lexical order. The JVM evaluator (including its
suspendable calling path) validates the proof **before any source effects**,
then evaluates each supplied value exactly once in source order, finally
reordering evaluated objects—not AST expressions—to the formal ABI slots.
Invalid/unknown labels, duplicated targets, positional-after-named,
unchecked call identities, syntactic spread, generic calls and non-scalar
payload types remain rejected.

An actual Graal polyglot JVM guest JUnit test is authored for
`encode(y: mark(2), x: mark(4))` and expects stdout `24:42`, proving
source-effects-first ordering when executed. Other tests cover mixed arguments,
source-to-ABI provenance, ownership-only bypass, and metadata laundering.

**Important:** this stage does NOT enable optional formals, default
materialization, typed None, named methods, constructors, imports, dynamic
callables, or broad generic named calls. `calculate(x:20,y:22)` with an
optional `z?` still fails closed. The JUnit execution tests have not yet
run on a working JDK25/Graal runner, so no verified guest result is claimed.

## Checked executable scalar omission/default stage

Added a restricted `CheckedScalarFormalPolicy` used by admission,
TypeChecker, OwnershipChecker, and the synchronous/suspendable JVM
invocation paths. Only top-level, synchronous, non-generic free functions
with plain `int` results and `int`/`Option<int>` formals are admitted;
omittable `int` requires a literal signed `Long` default and omittable
`Option<int>` permits only implicit or explicit `None`. No arbitrary
default evaluation or host null coercion is allowed.

The call binder evaluates input expressions in original lexical order,
then reorders the evaluated values to formal ABI positions and fills
omitted int/default/Option None slots. No omitted slot may execute
a caller expression. Overlapping optional-arity overload candidates
fail closed. A dedicated JUnit guest suite targets both parameter orders,
nontrailing default slots and the exact `calculate(x:20,y:22)` case.
This is scoped to direct functions; methods, constructors, generics,
lambda default parameters and other backends remain unsupported.

**All tests are AUTHORED / NOT_EXECUTED** pending a working JDK25
GraalVM runner. Typechecking/ownership and guest correctness are
hypotheses until that stepful run succeeds; do not merge on this basis.
