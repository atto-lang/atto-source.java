# Named and non-trailing optional value arguments: hardened language contract

Status: **staged language design, NOT yet executable guest syntax**. The original
T?-only contract below is extended by the 2026-10-18 Option-type omission
amendment. The amendment describes the *next language mode*, not the current
source compiler. Existing Option<T> parameters retain their legacy required
behavior until a versioned or capability-gated migration is implemented.
See [Option-typed formal omission amendment](#option-typed-formal-omission-amendment-2026-10-18).
Java/GraalVM currently has an arity-based runtime, ordinary expression-list calls,
and exactly one explicitly declared class constructor. The LLVM native scalar subset
already parses and lowers named calls at **exact** arity, but cannot omit arguments;
its narrower implementation is not evidence of full Java/LLVM parity.
Passing the pure-Java call-shape tests alone does not enable this feature.

## Canonical source forms

```oreslang
pub fnc foo(int a, string? b, bool c): int {
    // Inside foo, b has static type Option<string>.
    return 5;
}

foo(3, "", true);       // valid: a=3, b=Some(""), c=true
foo(a: 3, c: true);    // valid: a=3, b=None, c=true
foo(3, c: true);      // valid: positional prefix then named suffix
foo(c: true, a: 3);   // valid: labels can be reordered
foo(3, true);         // error: true fills b's slot, c remains missing
foo(a: 3, true);      // error: positional argument follows named
```

The `?` belongs to the **formal argument slot** only, directly after its
declared type: `T? name`. It does NOT alter a general type reference, make
`T` nullable, or introduce JavaScript's `undefined`. In **legacy mode** this differs from
`Option<T> name`, which currently requires an explicit Option value. In the
proposed **implicit Option omission mode** both forms become omittable and
have identical body-visible `Option<T>` types. To insist on a supplied
Option in the new mode, use the proposed `required Option<T> name` formal. In the callee, `T? name` is read as an `Option<T>`;
a supplied ordinary `T` is wrapped in `Some(T)`, an already-typed `Option<T>` value may be passed as-is, and omission is `None`.
The omitted/default distinction must remain visible to match, borrowing,
serialization, reflection, debugging and cross-backend lowering.

Optionality is orthogonal to positional placement. Unlike JS/TS trailing
optional rules, `T? name` can precede required `U later`; omission of a
middle slot **requires a label for subsequent provided slots**. There are
no positional holes, no shifting, and no implicit type-directed repair.
Conventional positional calls may omit only a suffix consisting entirely
of optional parameters.

## Statically checked binding algorithm

1. Resolve visible callable owners and candidate declarations; do not
   evaluate guest argument expressions or choose by runtime argument types.
2. Parse first-class call items `(label?, expression, spread?, source span)`.
   Evaluate receiver, then every supplied value expression **once in
   lexical source order**, preserving borrow/move/effect and exception order.
3. Bind positional items to slots 0, 1, ... with no skipping. Once a labeled
   item occurs, reject any later positional item. Match labels by exact
   case-sensitive formal name. Reject unknown, duplicate, or collided slots.
4. Missing slots must be declared optional. Materialize compiler-generated
   typed `None` for omitted optionals and `Some(value)` for supplied optional
   slots. These wrappers are not additional evaluations of user expressions.
5. Type-check each supplied expression against its **underlying T** before
   lowering, and enforce ownership/capability restrictions using the binding
   plan. Neither a `None` nor `Some` may launder a forbidden move or borrow.
6. Emit the existing parameter-ordered ABI, with one slot for **every**
   declared parameter; ABI arity remains the number of declared value slots.
   No runtime label maps, lookup by parameter names, or late type matching.

Example call `foo(c: effect1(), a: effect2())` must evaluate effect1 before
effect2, then pass `(a=effect2, b=None, c=effect1)` in declaration
order. For `a` and `c`, use the declared required types directly. A plan
mapping formal -> source argument indexes is `[1, -1, 0]`.

### Overload safety and compatibility

- Existing same-declared-arity collisions remain invalid (subject to a
  separately accepted nominal-enum overload contract, which **must not**
  distinguish declarations only through optional markers or labels).
- For the conservative v1 release, an optional-capable declaration
  has a supplied-count interval [number of required slots, total slots].
  Reject at **declaration/link time** any pair of overloads whose intervals
  overlap, even if their named-label sets appear disjoint. For example
  `f(int a)` and `f(int a, bool? flag)` cannot coexist. This intentionally
  rejects some theoretically disambiguatable calls until all compiler
  backends have a proof-carrying stronger call-shape selector. Do **not**
  prefer exact arity, guess by runtime type, or depend on declaration order.
  Once the family passes admission, every call still binds to one signature
  by positional prefix and exact named labels.
- After structural selection, the existing callable selector uses the
  selected declaration's full value arity. Where inherited methods or
  interfaces are involved, an override must preserve each parameter name,
  slot order, and optional bit. Changing them is an ABI-compatible but
  source-contract-breaking change, and must be rejected for overrides.
- Named arguments on opaque/unlabeled function values, foreign calls,
  dynamic dispatch without stable labels, builtins, and open variadic
  families fail closed until a stable static signature is available.
- Preserve the preexisting constructor `self`-receiver rule: an explicit
  leading construction-scoped `self` is **not** a user value parameter and
  is excluded from both declaration arity and binding plans.

### Constructor application

```oreslang
define class Job as
    val int id;
    constructor(int id, string? note, bool enabled) {
        self.id = id;
    }
end

val job = new Job(id: 9, enabled: true); // note=None
val job2 = new Job(9, "", true);        // note=Some("")
```

Ordinary-class constructor calls use exactly the same shape binder, typed
omission rules, and effect/ownership ordering. Definite initialization of
`val` fields is unchanged: omitted arguments are not a way to skip required
field initialization. Constructors cannot leak a partially initialized
`self`. Actor constructors remain forbidden. The current Java parser allows
only one explicit constructor per class; supporting multiple constructor
overloads, where subsequently implemented, remains subject to **effective
user arity** identity and ambiguity checks, not type or label overloading.

## Hardened exclusions (initial release)

- Reject an optional marker on `Option<T>?` in v1; it would create an ambiguous nested-Option lifting rule.
- Reject `T?` outside formal parameter declarations (field types, returns,
  local variables, type generic arguments) unless a separate nullable-type
  feature is specified. For now prefer `Option<T>` there.
- Reject `borrow T?`, `borrow mut T?`, mutable/structural optional formals,
  optional receivers, and optional rest/spread formals until loans, affine
  ownership and exact lowering are proven for missing arguments.
- Reject mixing named value arguments with a spread (`...expr`) pending a
  separate static tuple-expansion and source-order proof.
- Defaults like `T? x = expr` and `T x = expr` are **not** part of this
  contract; there is no default expression evaluation.
- Do not map missing optional values to empty strings, `null`, zero, false,
  `undefined`, a guest sentinel, or a borrowed dangling placeholder.
- Do not implicitly rewrite `call(name = value)` into `call(name: value)`.
  Named generic *types* continue to use `<Name=Type>` independently.

## Required test and integration gates

Test functions, routines, instance/static methods, interface inheritance,
constructors, namespace and generic calls. Include reordered effects,
exceptions, evaluation-once, move/borrow exclusivity, callback reentrancy,
async/cancel, tail calls, formatting, AOT/hybrid, LLVM -O0/-O2, and ABI
fingerprints. Reject unknown/duplicate/collided labels, missing required
values, excess and late positional arguments, ambiguous overload families,
optional-formal type misuse, bypass through indirect calls, and invalid
`self` constructor handling.

Implementation milestone 0: `OptionalCallShapeBinder` is a pure Java
compile-time planning utility with regression tests. It does **not** parse
or execute new source forms. Feature admission requires AST/parser changes,
an actual type/effect/ownership check plan, runtime and lowering integration,
LLVM parity, formatter and grammar changes, and execution of the same
negative and positive guest programs on matching exact Git trees.

## Option-typed formal omission amendment (2026-10-18)

**Proposed direction, not executable syntax:** infer argument omittability from
the **resolved intrinsic Option<T> formal type**. `T? name` is purely an
abbreviation for `Option<T> name` in the new language mode. A required
`Option<T>` formal remains expressible using the contextual
`required Option<T> name` modifier. This is a deliberate API behavior
change relative to legacy Oreslang, so the change MUST be gated by
versioned module/signature metadata during rollout. Do not silently
reinterpret old precompiled APIs or source when importing them.

```oreslang
pub fnc foo(int a, Option<string> b, bool c): int {
    // b is Option<string>.
    return 5;
}
foo(3, "", true);             // supplied string -> b = Some("")
foo(a: 3, c: true);          // omitted -> b = None
foo(3, _, true);             // explicitly omitted -> b = None
foo(3, None, true);          // supplied Option.None; b = None
foo(3, Some("x"), true);     // supplied Option; b = Some("x")

pub fnc audit(required Option<string> token): void {
    // token may hold None, but the caller must supply an argument.
}
audit(None);                 // valid: explicit supplied None
audit();                     // error: required argument absent
```

**Presence is not value.** Implicit omission, explicit standalone `_` /
`omit`, and a caller-supplied `None` can all yield the same callee value
`None`, but their checked call-plan presence/origin metadata differ.
Later defaults may react to an omitted argument without rewriting an
explicit `None`; never re-evaluate or accidentally discard a side-effect
of a supplied expression. `None` is the existing Oreslang value spelling;
do not assume `Option.None` is an available expression namespace.

**Static typing and lifting.** Only the nominally resolved intrinsic
`Option<T>`, including aliases/generic instantiations whose resolved type
is proven, gains automatic omission. Never use a textual name prefix,
structural `Some`/`None` members, a user-defined `Option`, dynamic
dispatch, or runtime argument types to infer omittability. At an omittable
formal boundary, an exact `T` becomes `Some(T)`; an exact
`Option<T>` (including explicitly typed `None`) is passed unchanged.
A `required Option<T>` formal accepts only a properly typed Option
value, not implicit `T -> Some(T)` lifting. General assignments do not
gain implicit Option promotion.

**Nested cases.** `Option<Option<T>>` is omittable because its outer
container is Option, but an unbound slot becomes *outer* `None`.
Do not automatically lift an `Option<T>` expression to
`Some<Option<T>>` without an explicit, type-checked outer constructor:
it can erase the distinction between outer `None` and `Some(None)`.
The spelling `Option<T>? name` is invalid: it is neither a second
default nor implicit `Option<Option<T>>`; express nesting explicitly.

**Variadic and ownership limits.** A final `...Option<T> rest` parameter
is a variadic sequence with zero or more optional-typed *elements*;
an absent tail is the empty sequence, not an omitted Option slot. An
omittable mutable/borrowed/structural parameter remains fail-closed
until the relevant loan/drop proof is implemented. Function, routine,
method, interface, lambda and explicit class-constructor signatures
must eventually share the same rule; synthesized field-order
constructors, actor constructors and message payload schemas are not
silently rewritten.

**Overload ABI and compatibility.** Required vs omittable is part of a
versioned callable contract and must be inherited unchanged in overrides.
Conservative v1 effective-arity interval collision checks apply to
**all** implicitly omittable Option slots as well as `T?` slots.
The compiler must select a complete declaration identity and retain
full ABI parameter arity, without choosing by runtime argument types,
labels, or declaration order. Keep the legacy required-Option mode
until all Java/Graal/LLVM frontends and imports can enforce this.

**Implementation checkpoint.** `OptionFormalPresencePolicy` is a
pure, executable Java classifier distinguishing LEGACY from
IMPLICIT_OPTION_OMISSION and preserving omitted-vs-supplied outcomes.
It receives **already verified nominal type classifications** and
does not itself parse a `required` modifier, decide actual argument
types, execute effects, or lower Option values. No named/optional
guest calls may execute until the parser, type/effect/ownership checker,
constructor semantics, formatters and both backends have parity proofs.

## Absence, explicit Option values, and variadic tails (2026-10-18 audit)

This contract does **not** make `void` a value or make `null` synonymous
with absence. `None` is a typed `Option<T>` variant, so a caller may
explicitly supply `None` to a `T? name` slot or simply omit the slot. A
plain `T` is lifted into `Some<T>`; a type-checked `Option<T>` value
passes unchanged. The implicit lifting happens *only* at an optional-formal
boundary, not in general assignments. The compiler must reject `void` as
an expression argument even if a called routine returns no result, reject
`null` for `T?`, and reject empty positional holes such as `f(3,,true)`.
A separate nullable type, if introduced later, must remain distinct.

```oreslang
foo(3, None, true);         // valid: b is Option<string>.None
foo(3, Some("x"), true);    // valid: b is Some<string>("x")
foo(3, void, true);         // error: void is not a value
foo(3, null, true);         // error: null is not None
foo(3,,true);               // error: no empty positional grammar
```

**Variadic contract — pure-binder stage only, surface spelling provisional:**
a declaration may have at most one `...T items` parameter and it must
be the final formal. It is **not** optional (`?` is forbidden there).
The callee sees an owned/borrow-checked typed collection of `T`; zero
supplied tail elements is an **empty collection**, not `None`. Each
positional argument before rest binds to the next fixed formal even if
that formal is optional; remaining positional arguments form the rest
elements in lexical evaluation order. Positional elements after any
named item remain illegal. Named binding directly to the rest parameter
and any spread expansion (including named + spread) are gated until an
independent statically typed collection/tuple-expansion plan is proven.
The user may still use named arguments for fixed slots and leave rest
empty. `None` as a rest *element* is legal only when the element type
itself is `Option<T>` and the normal type checker proves it; `None`
never means "zero rest arguments".

Illustrative non-executable declaration:

```oreslang
pub fnc log(string? prefix, ...string messages): void { /* ... */ }

log();                          // prefix=None, messages=[]
log("title", "a", "b");        // prefix=Some("title"), messages=["a","b"]
log(prefix: None);              // prefix=None, messages=[]
log(prefix: None, "hello");     // ERROR positional after named
log(prefix: "x", messages: xs); // ERROR named-rest pack not yet specified
```

Overload admission treats a rest tail as a **supplied-argument range with
no finite upper bound**: `[required_fixed, infinity)`. Any intersection
with a fixed or optional overload count range is rejected at declaration
or link time. Thus `f(int x)` cannot coexist with
`f(int x, ...int tail)`. Overloads must never resolve by trying
parameter types, labels, nullable coercions, or declaring order.
Constructor overloads follow effective *user* arity and this same
intersection rule, excluding a leading construction-scoped `self`.
Actor constructors remain prohibited.

The Java pure binder now plans rest source indexes and optional value
adaptations, while the LLVM JSON/Python oracle includes adversarial
null/void/rest cases. **These are tests of a proposed static contract**,
not evidence that either frontend parses optional or rest declarations,
that typed Option wrappers lower in any backend, or that CI/runtime parity
is established. Dynamic and foreign calls remain fail-closed.

## Positional omit marker and canonical variadic spelling

The current shared v1 contract also defines a context-sensitive **`omit`
call item**. It is not an expression and has no type or evaluated value.
Only an OPTIONAL FIXED formal may receive it; it consumes one formal slot,
and the compiler injects typed `None` in that slot. Omission through a
named call is still preferred when readability permits.

```oreslang
foo(3, omit, true);         // valid: explicit omission of optional b
foo(3, None, true);         // valid: explicit Option<string>.None value
foo(3, "", true);           // valid: Some("")
foo(3, void, true);         // invalid: void is not a value or omit marker
foo(3,,true);               // invalid: no double-comma holes
```

Unlike `None` (a typed Option value), `omit` is a syntax-level event:
it contributes NO argument expression, no borrowed value, and no move.
Diagnostics and debug metadata may preserve the distinction between
`explicit_omit`, `implicit_omit`, and `explicit_none`, but all three
produce the same callee-visible Option<T>.None variant for a T? formal.
It is forbidden on required formals and on rest elements. `void? name`
and `Option<T>? name` declarations are rejected in the initial profile.

Canonical trailing variadic declaration syntax is **`...T restName`**,
NOT `rest ...T restName`. Zero rest arguments produce an empty readonly
typed pack/slice, never `None`. Named `restName: List<T>` packs and
a single final `...List<T>` spread are tracked by the shared reference
spec but remain gated on compiler ownership/type/evaluation proof.
The Java pure binder models fixed optional slots, the `omit` marker
and positional rest; it intentionally rejects named rest and spreads.

For an illustrative declaration `fnc log(string? prefix, ...string
messages)`, `log(omit, "first", "second")` omits `prefix` while
providing TWO rest elements. The compiler must preserve actual source
expression indexes `[0, 1]` for those elements—`omit` cannot create
a hole in the expression-evaluation permutation.


## Underscore omission alias

In call argument item position, a standalone `_` is an exact syntactic
alias of `omit`. Both parse as `OmittedArgument`, with NO runtime value,
NO expression evaluation and NO effect/borrow/move. They consume one
optional fixed formal slot, without changing the dense source-index map
of actual evaluated expressions.

```oreslang
log(3, _, "first", "second");    // optional label omitted; two rest values
log(3, omit, "first");           // identical semantics
log(3, None, "first");           // explicit Option<string>.None VALUE
log(priority: 3, label: _);     // omission of named optional fixed slot
```

The underscore token remains a wildcard in pattern contexts, and a
discard/wildcard where separately supported, but **never** becomes a
general-purpose undefined value or implicit assignment. Parser checks
must recognize a **bare** `_` only at a complete call argument item
(not a member access or part of a longer expression). It is rejected
for required formals and every variadic/rest tail element. The formatter
may prefer `_` as the canonical call spelling, but must preserve
the semantic difference from `None`. Tests must cover duplicate
binding, named omission, typed `None`, and evaluation ordering.

## Contextual '_' call item: same semantics as 'omit'

`_` is the concise, **contextual call-item alias** for `omit`:
`foo(3, _, true)` and `foo(3, omit, true)` normalize to the same
`ArgumentItem.Omit`, with zero evaluated argument expressions for that
slot, and no borrowed, moved or default value. Named `foo(b: _)` is
equivalent to `foo(b: omit)`. Both consume a specific optional fixed
formal slot, preserve exactly-once evaluation of other source expressions,
and inject a typed `Option<T>.None` in the callee.

Do **not** erase the distinction between these omission markers and an
explicit `None` *value expression* (`Option<T>.None`), which is supplied,
typed and recorded as `explicit_none` in call metadata. The callee can
observe the same `Option<T>.None` in both cases, but metadata retains
`implicit_omit`, `explicit_omit` and `explicit_none`. The `_`
spelling does not introduce a first-class runtime value, partial function
application, a null conversion or an unbound identifier. Existing
destructuring/match wildcard `_` remains unchanged.

```oreslang
pub fnc log(int priority, string? label, ...string messages): void {}

log(3, _, "first", "second");       // label omitted, rest length 2
log(3, omit, "first", "second");    // identical binding
log(3, None, "first");             // explicit Option.None value
log(priority: 3, label: _);        // named omission, rest length 0
log(priority: 3);                  // implicit omission
```

Contextual-only grammar: the token `_` is special only as the entire
positional or named call argument item. The formatter may retain either
spelling without affecting semantics. Reject `_` on required or variadic
rest parameters; reject empty comma holes, `void` and `null` in lieu
of omissions. No runtime guest-language support is established by pure
binder tests: parser/AST, type/ownership/Option lowering, constructor
handling, and Java/LLVM parity remain gated.

## Lowering proof: original call-item indexes versus evaluated-expression indexes

Do **not** use a dense evaluated-argument index directly to index the original
call AST after parsing explicit `_` / `omit`. Such markers occupy source
items without creating expressions; using the wrong index can accidentally
evaluate an omission node or evaluate an entirely different expression.

For `foo(3, _, effect())`, the raw call-item indexes are `[0,1,2]`.
The **evaluated expression list** is `[3,effect()]`; the immutable
`callItemIndexByEvaluatedSource` map is `[0,2]`. A formal-to-evaluated
mapping might be `[0,-1,1]`. Therefore the third formal reads evaluated
source 1, which resolves to raw AST item 2. A named reordered call such as
`foo(c: effectC(), b: _, a: effectA())` also produces raw evaluated item
map `[0,2]`, while the formal-to-evaluated mapping is `[1,-1,0]`.
The receiver/callee must be evaluated once before all present arguments;
arguments then evaluate **lexically in source order** regardless of formal
order. `None` is a real Option expression and remains in the evaluated list.

The binder and reference vectors now preserve and test this correspondence,
but neither Java/Graal nor LLVM may claim guest-runtime support until AST,
type checking, ownership/effect checking, invocation lowering, and target
execution use the same immutable selected-call plan. Existing parsers that
still represent calls as `List<Expr>` must fail closed, not map `_` to
`NameExpr("_")` or silently enable source omission.


## Compiler stage update (2026-10-18): structured call items, no executable lowering

The Java parser now produces distinct `Ast.CallArgument` records for each
source call item: optional `label`, either `value` or `omitted`, and an
explicit `spread` flag. An omitted item **has no `Ast.Expr`**.
Normal positional calls retain `Ast.CallExpr` and `Ast.NewExpr` unchanged.
Calls with named/omitted items instead produce `Ast.StagedCallExpr` or
`Ast.StagedNewExpr` (the latter records whether it is an actor spawn).
This is intentionally **syntax representation, not user-executable support**.

The staged AST rejects missing/present-value contradictions, duplicate named
labels, positional arguments after the first named argument, and unproved
constructor spread forms. The `OptionalCallShapeBinder.parsedArgumentShapes`
adapter translates parsed call items into pure shape descriptors while
preserving raw indexes, but rejects spread pending typed collection proof.
The type checker, ownership checker and interpreter independently fail closed
for staged expressions: no runtime dispatch, Option wrapping or ownership
transfer may be inferred simply because a call parsed successfully.

Remaining acceptance gates: typed `T?` formals and `Option<T>` ABI;
static overload selection; precisely-once lexical evaluation; constructor
`self` erasure/definite initialization; ownership and effect checking;
formatter round-tripping; and Java/GraalVM plus LLVM execution/parity.
In particular, neither this stage nor pure reference-model success is a
passing compiler test.

## Staged optional formal metadata (2026-10-18)

Java now parses canonical `T? label` and compatibility `label: T?`
formal declarations into a *compiler-only type marker* held by `Ast.Param`.
The existing four-component `Ast.Param` construction interface is preserved.
`Param.optional()` reports the source omission contract,
`Param.valueType()` yields source `T`, and `Param.calleeType()`
constructs the intended `Option<T>` type. This differs from a required
`Option<T> label` formal: the latter has `optional() == false`.

The special marker is only recognized in a formal-parameter position.
Standalone type expressions and `void?` are not enabled, and structural
borrowed view parameters cannot be omitted. Pipe lambdas and class
constructors preserve the same metadata. The static binder's
`declaredParameterShapes` converts these `Ast.Param` records to omission
signatures, and `evaluatedExpressionsInSourceOrder` verifies the selected
call plan against the original AST before yielding expressions in lexical
evaluation order. It does *not* execute them.

There is **no** optional-parameter ABI/runtime support yet:
`TypeChecker` and `OwnershipChecker` reject all staged `T?`
declarations before normal admission. Linked class signatures and lambda
formals are also checked; staged named/omitted calls continue to fail closed.
Do not infer success of actual program execution from parsing, JUnit source
presence or standalone reference checker passes.

Release gates remain full Option<T> ABI and effect/borrow checking, unique
target selection, checked constructor and actor lifecycle binding, native
LLVM parity, and green JDK25/GraalVM and LLVM CI tied to exact source trees.

### Staged static selection and direct Truffle-root safety

The binder's `selectParsed(declarationSignatures, sourceItems)` selects a
disjoint arity domain at compilation time and rejects any overlap before
argument labels can influence overload identity. It verifies the resulting
immutable plan against the original AST; actual expression evaluation is
still gated. `evaluatedExpressionsInSourceOrder` yields the source expression
references in lexical order only after that plan correspondence is proved.

The public compiler entry point, imported-class type/ownership checker paths,
lambda checks, and now the direct `OresEvalRootNode` AST constructor all
reject unlowered `T?` declarations. This closes a path where callers could
construct a runtime root without first using the normal checked compiler API.

These safeguards **do not imply** that named/optional invocation is
executable or that JUnit/compiler CI has passed. The protected source
organization currently lacks step-backed test evidence for the candidate
head; private source transfer requires the separately reviewed allowlist.


## Staged-formal AST admission audit (2026-10-18)

The Java parser and AST now reject these formerly under-validated declaration shapes
at construction time: `Option<T>? x`, `void? x`, `borrow T? x`,
`borrow mut T? x`, `T? mut x`, and a `T?` containing a nested borrowed
type or omittable marker. An ordinary generic type cannot directly contain
the compiler-only omittable-formal marker. A required `Option<T> x` remains
valid and distinct from `T? x`.

In addition to checking function, method, interface and constructor formals,
the pre-lowering guards reject synthetic misuse of a `T?` marker in field,
return, inheritance/implementation, receiver and alias positions. The
type checker repeats the signature check after annotation expansion. Direct
AST construction is not permission to bypass these gates.

Tests for parser/AST rejection and independent type/ownership admission are
committed on Java PR #1335. **These are not executed-JUnit results.** GitHub
Actions on the current private source branch reports failures without usable
job steps or logs. Protected private-source export for alternate CI remains
unapproved; do not mirror the repository to another organization without
the approved allowlist. The runtime, ABI, ownership adaptation, and LLVM
implementation remain intentionally disabled.


## Typed ABI preview milestone (2026-10-18)

Java PR #1335 now contains `OptionalCallAbiPreview.prepareExact`, an
**untrusted, non-executable** typed call-plan sketch. Given the selected
formal signature, the original parsed call items, a recomputed/verified
`OptionalCallShapeBinder.Plan`, and one independently checked *exact*
resolved value type per supplied expression in lexical order, it records:

- `PASS_REQUIRED`: unchanged required value;
- `WRAP_SOME`: exact `T` supplied to `T?` becomes one `Some<T>`;
- `PASS_OPTION`: exact `Option<T>` supplied to `T?` passes unmodified;
- `INJECT_NONE`: an implicit or explicit omission of `T?` generates typed
  `Option<T>.None` at the ABI slot (with no evaluated expression).

The immutable preview has one slot per **declared value parameter**, in
declaration order; source expressions are retained only once in lexical
order and referenced by dense indexes. The constructor rejects duplicate,
missing or out-of-order source bindings and duplicate raw call-item origins.
Explicit `_`/`omit` retains its original call-item index; implicit omission
uses -1. Already-typed explicit `None` remains a **supplied expression**,
not an omission.

The preview deliberately rejects unresolved, void, borrowed, inexact or
unknown argument types, and refuses variadic pack lowering. It does not
implement aliases, generic substitutions, subtyping, callback/effect/loan
proofs, native ABI emission, actual `Some`/`None` object construction or
runtime invocation. Its input `evaluatedTypes` must be validated independently
by the type checker: the helper cannot trust caller-supplied type assertions.
No executable code path consumes the preview; neither the Java interpreter
nor LLVM may bypass existing fail-closed gates because it exists.

A dedicated `OptionalCallAbiPreviewTest` source covers reordered named
calls, omission origins, required-vs-optional `Option<T>`, exact `Some`
lifting, immutable results, forged-plan rejection, and unsafe/unproved
types. **These are committed JUnit tests, not green executed JUnit evidence.**
Full compiler/type/ownership/runtime integration remains the next gate.

## Compiler-only invocation-order preview (Java PR #1335)

`OptionalCallAbiPreview.prepareStagedCallExact` and
`prepareStagedConstructionExact` produce factory-owned *non-executable*
`InvocationPreview` descriptions. Ordinary function/method calls contain the
original callee AST expression as the **first** evaluation step; the callee
expression includes any receiver evaluation. Every supplied call-item value
then appears **exactly once in lexical source order**. An explicit `_` or
`omit` contributes no evaluation step. Class constructors and actor spawns
have no guest callee-expression step, and retain separate target kinds for
future ownership/capability checks. Formal ABI slots are separately ordered
by declaration, not by evaluation order.

These factories rebuild the binding plan from the exact staged AST rather
than trusting an externally supplied call-item list. A top-level
`Ast.LiteralExpr(null)` is rejected even when its claimed type is forged.
Rest/spread packing, explicit generic call type application and generic or
borrowed construction targets remain rejected in these narrow previews.
The independent selected owner, visibility, resolution, subtype/coercion,
loan/move/effect checks, constructor self rules and executable Option ABI
are **not proven by a preview**. The compiler, ownership checker and
interpreter continue rejecting staged guest calls and `T?` formals;
this is not a green Java, GraalVM or LLVM runtime feature.


## Literal-derived argument proof (draft Java PR #1335)

The compiler-only `OptionalCallLiteralStage.prepareDirectLiteralCall` checks a
deliberately narrow, independently typeable source subset: supplied values must
be literal `int` (Long), `float` (Double), `bool` (Boolean), or `string`
(String) AST nodes, or omitted call items. The literal type is derived from
the AST instead of accepting caller assertions, then the existing original
call-item binder, type-exact ABI preview and lexical-evaluation schedule are
applied. Supplied `T` to `T?` plans `WRAP_SOME`; omission plans
`INJECT_NONE` without evaluating a source expression.

General `OptionalCallAbiPreview.prepareExact` also rejects a scalar
literal claimed to have a different exact TypeRef, not merely `null`.
Complex/symbol/custom literal categories remain gated until they have
independent canonical source-type proofs. Plain `None` is not a primitive
literal: it remains a typed expression requiring normal resolver proof,
never an implicit omission.

**Not an executable call witness:** even direct NameExpr callees can be
shadowed or inaccessible, and this literal stage proves neither target
identity/overload, generic substitution, effects, ownership, nor value ABI
layout. The type checker, ownership checker and interpreter continue to reject
staged optional calls; this stage must never be used as permission to bypass
those guards. The next gate is compiler-owned resolution and ownership
verification, then checked Option materialization in each backend.


## Constructor receiver erasure admission audit (2026-10-18)

The non-executable Java ABI preview now **rejects a constructor formal
list containing a parameter named `self`**. The construction-scoped
receiver is not a user value slot and must have been erased *before*
forming a source call-shape/ABI plan; a named call item `self: value`
cannot bind the receiver or satisfy any definite-initialization
obligation. The preview keeps one ABI slot per effective user
parameter, never per raw syntactic constructor binder. This extra
guard supplements (rather than replaces) trusted constructor-owner
resolution and ownership/definite-initialization checking.

Regression sources cover forged `self` formals, forged named `self:`
calls, and the valid receiver-erased one-slot constructor plan.
These are **committed tests, not evidence of successful repository
JUnit execution**. Do not enable this feature at the interpreter
or LLVM runtime boundary until the full proof chain and exact-head
CI passes.

Private source mirror status: `main` policy file
`.github/private-ci-approved-repositories.txt` still lists no
approved destination, and security review PR #1326 remains draft
despite successful *source-free* Actions in a private alternate
organization. A user-requested mirror is **not** a substitute for
protected-branch CODEOWNER/security approval. No source bytes were
transferred by this audit.


### Mandatory explicit function returns and value-match arrow expressions

Every non-`void`, non-generator function or concrete method must explicitly
`return` a value on every reachable completion path. The last expression in
a function body is **not** an implicit return. Returning a value-producing
`match` uses a callable-level `return match ... end`.

A `match` expression may use concise arrow expressions: `on Some(x) -> x`.
Those arm expressions produce the value of the `match` expression; they
**do not** return from the enclosing function on their own. Existing braced
`on Some(x) -> { return x; }` value arms remain compatible; their
`return` is scoped to the match expression and is never a function return.
`do match` is procedural and cannot appear as a returned expression.

```ores
pub fnc greet(string? name): string {
    return match name over
        on Some(n) -> n
        on None -> "anonymous"
    end
}
```

The syntax above is a **parsing contract only** while optional `T?`
parameters are still staged. Type-checking and execution remain denied
until the typed Option ABI and ownership admission are implemented.


## Parser-owned local literal candidate preview (2026-10-18)

`OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(program, call)`
derives a candidate function's formal parameters from an **actual parsed**
single-module `Ast.Program` instead of accepting caller-supplied formal
metadata. The compiler-only result combines a parsed `FunctionDecl` with
the independently typed scalar-literal ABI/evaluation preview.

Admission is intentionally narrower than eventual language semantics:
programs with imports or multiple modules, annotated/contracted modules,
same-name field collisions, overloaded names, unknown labels, generic call
application, effectful/nonliteral arguments, callable generics, async,
suspending, generator, nonlexical, trapped, actor-owned and annotated
functions are rejected. All candidate formal value types are additionally
restricted to exact `int`, `float`, `bool`, or `string` scalar types;
structural, mutable, borrowed, unresolved nominal, generic and required
`Option<T>` formals cannot acquire a fabricated literal proof. These exclusions may later be relaxed only after
the real type/lexical/ownership resolver proves them. The helper does not
inspect or authenticate the lexical call-site location: a local variable
may still shadow a module function, and merely matching a parsed direct
name does **not** establish resolution, accessibility or authority.

This is **not** a compiled call, an executable IR token, or a replacement
for `TypeChecker` / `OwnershipChecker`. The Java interpreter and native
backends continue rejecting staged calls and `T?` formals. A compiler
integration must authenticate lexical owner, declaration identity,
overload family, generic substitutions, source expressions, effects,
loans/moves and checked Option<T> ABI before enabling invocation.

New JUnit regression *source* covers a named reordered call with explicit
omission and direct parsed declaration identity, plus duplicate/same-name
declarations, imports, annotations, async, unknown names/labels, generic
calls and nonliteral effects. The source organization Actions jobs are
still failing before recording execution steps, so no green JUnit claim
can be made on this branch.

The protected `main` private-source export allowlist remains empty.
Security approval PR #1326 remains draft and CODEOWNER-reviewed approval
is a prerequisite for any cross-organization private source mirroring.
Alternate private destination's previously successful synthetic workflows
are *not* evidence of compilation; recent checked destination jobs also
failed with zero recorded steps. No source transfer was performed.


## Value-match cleanup/return hardening

`ReturnCleanupChecker` must traverse each value-producing `MatchExpr`
subject, pattern-scoped arm guard, and selected arm value, including nested
matches. An assignment hidden in a deferred match is still a deferred
reassignment of the returned binding and must fail the same check as a direct
assignment. Pattern-local shadowing must not be mistaken for reassignment of
the outer binding. Unlowered staged named/omitted calls and unreviewed AST
expression variants fail closed instead of silently skipping cleanup checks.

The checker regression methods establish expected safety behavior in source.
They do **not** prove actual Maven/JUnit, compiler or runtime execution until
an executing CI runner validates the exact Git tree.


### Mandatory explicit function returns and value-match arrow expressions

Every non-`void`, non-generator function or concrete method must explicitly
`return` a value on every reachable completion path. The last expression in
a function body is **not** an implicit return. Returning a value-producing
`match` uses a callable-level `return match ... end`.

A `match` expression may use concise arrow expressions: `on Some(x) -> x`.
Those arm expressions produce the value of the `match` expression; they
**do not** return from the enclosing function on their own. Existing braced
`on Some(x) -> { return x; }` value arms remain compatible; their
`return` is scoped to the match expression and is never a function return.
`do match` is procedural and cannot appear as a returned expression.

```ores
pub fnc greet(string? name): string {
    return match name over
        on Some(n) -> n
        on None -> "anonymous"
    end
}
```

The syntax above is a **parsing contract only** while optional `T?`
parameters are still staged. Type-checking and execution remain denied
until the typed Option ABI and ownership admission are implemented.


## Parser-owned local literal candidate preview (2026-10-18)

`OptionalCallLiteralStage.prepareSingleModuleLiteralCandidate(program, call)`
derives a candidate function's formal parameters from an **actual parsed**
single-module `Ast.Program` instead of accepting caller-supplied formal
metadata. The compiler-only result combines a parsed `FunctionDecl` with
the independently typed scalar-literal ABI/evaluation preview.

Admission is intentionally narrower than eventual language semantics:
programs with imports or multiple modules, annotated/contracted modules,
same-name field collisions, overloaded names, unknown labels, generic call
application, effectful/nonliteral arguments, callable generics, async,
suspending, generator, nonlexical, trapped, actor-owned and annotated
functions are rejected. All candidate formal value types are additionally
restricted to exact `int`, `float`, `bool`, or `string` scalar types;
structural, mutable, borrowed, unresolved nominal, generic and required
`Option<T>` formals cannot acquire a fabricated literal proof. These exclusions may later be relaxed only after
the real type/lexical/ownership resolver proves them. The helper does not
inspect or authenticate the lexical call-site location: a local variable
may still shadow a module function, and merely matching a parsed direct
name does **not** establish resolution, accessibility or authority.

This is **not** a compiled call, an executable IR token, or a replacement
for `TypeChecker` / `OwnershipChecker`. The Java interpreter and native
backends continue rejecting staged calls and `T?` formals. A compiler
integration must authenticate lexical owner, declaration identity,
overload family, generic substitutions, source expressions, effects,
loans/moves and checked Option<T> ABI before enabling invocation.

New JUnit regression *source* covers a named reordered call with explicit
omission and direct parsed declaration identity, plus duplicate/same-name
declarations, imports, annotations, async, unknown names/labels, generic
calls and nonliteral effects. The source organization Actions jobs are
still failing before recording execution steps, so no green JUnit claim
can be made on this branch.

The protected `main` private-source export allowlist remains empty.
Security approval PR #1326 remains draft and CODEOWNER-reviewed approval
is a prerequisite for any cross-organization private source mirroring.
Alternate private destination's previously successful synthetic workflows
are *not* evidence of compilation; recent checked destination jobs also
failed with zero recorded steps. No source transfer was performed.

## Staged owner-bound module invocation plan (2026-10-18)

`BoundInvocation.bindModuleFunction` now binds a named/omitted call to an
**original parsed function declaration object** from the supplied module.
The result retains the owner, declaration ordinal, immutable source-first
invocation preview and formal-order ABI slot mapping. The candidate family
comes from the parsed module rather than an arbitrary name-to-signature
dictionary. The conservative optional-arity overlap policy is applied before
choosing a declaration. Both local names and precisely module-qualified
names have static shape support. The companion `bindClassMethod` and
`bindClassConstructor` factories record original directly-declared methods
and explicit non-actor constructors with the same immutable source/ABI plan.
These class-specific previews reject generic classes, inheritance, actor
construction, synthesized field-order constructors and targets not belonging
to the supplied parsed owner. Actual receiver typing and constructor field
initialization still require compiler proofs. Indirect calls and unproved
generics/actors are rejected.

This is **not a trusted executable IR**. Caller-supplied
`assertedSourceTypes` do not prove types; lexical import/visibility, effect,
ownership, receiver evaluation, coercions and `Option<T>` lowering remain
unimplemented. `requireExecutable()` always throws; existing independent
TypeChecker/OwnershipChecker/interpreter stage admission remains fail-closed.
JUnit test methods are committed, but passing JDK25 Maven or Graal/LLVM
guest-language runs is not yet established.

### Hardened shape-only admission (2026-10-18)

Additional fail-closed conditions prevent the preview from claiming that
effectful/async/generator/routine functions or annotated functions already
have a verified invocation contract. Class method previews now reject
static or abstract methods, explicit/specialized self receivers, structural
methods, async/suspending methods and methods with annotations until receiver,
dispatch, effect and `@CallableBy` authorization are proved. Annotated
constructors also require a separate authorization check. The direct-class
preview continues to reject abstract class construction, synthetic
constructor ABI and unlowered constructor `self` receiver erasure.

These checks narrow planning admission; they do **not** enable calls.
This IR still cannot establish caller lexical scope or trusted source types,
and `requireExecutable()` remains an unconditional error.

### Branch reconciliation and checked literal-only source types (2026-10-18)

The owner-bound invocation branch (Java PR #1397, stacked on #1335) now
preserves both histories in non-force merge commits, retaining #1335's
canonical `OptionalCallEvaluationSchedule` and its raw-call-item
`SourceBinding` provenance. The `BoundInvocation` constructor rebinds
against the *original* staged call items and selected declaration. Every
recorded ABI slot must agree with the formal name/type, dense expression
source index, and original omitted/supplied raw call-item position. Its
schedule is a single immutable source-first/ABI-second snapshot.

The public `bindModuleFunction`, `bindClassMethod`, and
`bindClassConstructor` factories still take **asserted**, untrusted source
types for structural/diagnostic planning only. No executable authorization
is derived from these values. Narrow companion `bindLiteral*` factories
instead derive exact primitive (`int`, `float`, `bool`, `string`) facts
directly from top-level literal-expression AST values; bare `None`, variable
references, calls, spreads, null and unsupported literals fail closed.
Even this narrow provenance is **not** sufficient for access, ownership,
effects, alias/generic substitution or runtime Option ABI proof.

Both paths return `SHAPE_ONLY` and `requireExecutable()` always throws.
The next authorization gate must be issued by the lexical TypeChecker and
OwnershipChecker after selected-owner visibility, receiver, generic,
borrowing/moving, effects, constructor initialization, and type adaptations
are checked together. Only then can compiler-owned temporary lowering be
enabled. Neither this stage nor the textual JUnit tests establish executable
guest-language parity; exact-tree JDK25/GraalVM/LLVM runs are still required.

Source-org Actions jobs have failed without recorded runner steps or logs.
Private cross-organization mirror transfer remains disallowed until separate
CODEOWNER/security approval on protected branch under PR #1326.

## Compiler-only literal lexical witness and receiver temps (2026-10-18)

The stacked Java #1397 branch now supplies two additional NON-EXECUTABLE
handoffs. They are not authorization to execute or a replacement for
TypeChecker, OwnershipChecker, native lowering, or a real JDK25 test run.

`OptionalCallTempLowering.from(BoundInvocation)` produces an immutable
recipe separating:

- Static module function: compile-time declaration selection, **no guest
  target-expression temporary**.
- Instance method: **one receiver expression temporary before all argument
  temps**, without pretending the method MemberExpr itself is a callable
  guest value.
- Explicit class constructor: no guest receiver or callee-expression temp.

Each evaluated lexical *occurrence* (not each AST object identity) creates
its own argument temporary, indexed by dense evaluated-source order with
its original raw call-item index. A supplied source maps to exactly one
formal ABI slot; full formal slots materialize only after every argument
temp and are annotated `PASS_REQUIRED`, `WRAP_SOME`, `PASS_OPTION` or
`INJECT_NONE`. Injected None has no source temporary, even when it
originated at an explicit `_` call item. The factory derives the mapping
from the immutable bound schedule; callers cannot fabricate executable
recipes through a public constructor.

`SameModuleLiteralInvocation.inspect(program, caller, call)` is a second,
narrow **lexical-origin preview**. It insists on original AST identity:
the calling declaration belongs to exactly one source module in the
supplied program and the call occurs exactly once as a direct top-level
statement, return, or binding initializer inside it. Only same-module
static direct/qualified calls are considered. Imported scopes, actor,
async/suspending/generator/routine/generic/annotated calling contexts,
parameter/module/local/destructure name shadowing and nonliteral
arguments fail closed. Its helper derives primitive source types from
actual literal AST nodes, not user-supplied claims.

A passed preview still lacks path-sensitive lexical binding in general,
visibility to other modules, trust for receiver/argument expression
effects, borrows/moves, constructor initialization, and executable
`Option<T>` layout. `requireExecutable()` remains an unconditional
exception. Full compiler-issued type/ownership/effect proof and backend
code generation remain distinct, mandatory future steps.

Regression methods are committed for lexical origin/forgeries,
receiver-before-argument ordering, distinct temporaries for repeated
AST object identities, explicit omissions, Option adaptations, imported
and shadowed names, and disabled guest execution. They are not passing
JUnit evidence until an actual runner executes them.

## Compiler-only hardening against lexical body reuse and phase confusion (2026-10-18)

Two additional **non-executable** audit gates now apply on stacked Java
PR #1397:

1. `SameModuleLiteralInvocation.inspect` no longer infers a unique lexical
   occurrence merely from finding the staged call directly in one statement.
   Because the narrow helper does not recursively establish CFG/effects, it
   refuses other complex statements and any other nonliteral expressions in
   the caller body. This prevents a second nested occurrence of the identical
   AST object, a prior side effect, or deferred work from being silently
   admitted as a trustworthy direct-call witness. Rejected shapes must go
   through the actual lexical TypeChecker/OwnershipChecker pipeline.

2. `OptionalCallTempLowering.Recipe.steps()` now exposes a separate immutable
   lowering-phase sequence: `CAPTURE_RECEIVER` (only a directly selected
   instance method), `CAPTURE_ARGUMENT` once per lexical source occurrence,
   `MATERIALIZE_ABI_SLOT` in formal order (including typed omission slots),
   and `INVOKE_SELECTED`. Statically selected module functions do NOT
   evaluate a guest callable object, and explicit class construction has no
   guest receiver evaluation. It is wrong to execute the general preview's
   `EVALUATE_CALLEE` as an actual member-expression value read in this
   specialized lowering. All phase indexes are checked and the step list is
   immutable.

These additions remain `SHAPE_ONLY`: the apparent `INVOKE_SELECTED`
operation is an IR ordering placeholder, not permission to call. Public
`requireExecutable()` still rejects every invocation. Added committed JUnit
sources test nested AST reuse, rejected unverified side effects/defer, static
versus method capture ordering, and invalid phase indexes. Source-org GitHub
Actions continues failing before test steps; the tests have not passed a
real JDK25/GraalVM/LLVM run.

The separately confirmed `sagitta-stack/sagitta-infra` GitHub Actions runner
has passed **source-free synthetic canaries** only. Protected `main` lists
no approved private CI destination, and security-review PR #1326 remains
draft. No private compiler source may be mirrored there before the policy is
reviewed and approved.


## Static scalar context witness

TypeChecker.inspectStagedScalarInvocation returns a non-executable CheckedScalarInvocationWitness. It derives literal value types from the original AST, selects an exact same-module declaration, checks a narrow scalar/void result-context contract, and rejects unverified caller signatures. The proof keeps BoundInvocation and OptionalCallTempLowering source-order mappings. It accepts no arbitrary claimed types.

This is a SHAPE_ONLY preview: requireExecutable() always throws, and full compiler/runtime gates stay closed until semantic, ownership, effect, and Option ABI lowering are verified. Tests are committed but have not passed JDK25 Maven CI. See CheckedScalarInvocationWitnessTest.


## Checker-issued scalar witness: deliberately checked callee body

`CheckedScalarInvocationWitness` must not interpret an AST-declared scalar
return type as proof that the selected implementation has that type. As a
strictly non-executable interim guard, issuance now requires the selected
ordinary module function to have exactly one return statement, containing
either an exact matching primitive literal or an unmodified required scalar
formal with exactly matching declared return type. A `void` callee must have
a single bare `return;`. A `T?`/Option-valued formal, computed return,
recursive call, other statements, incorrect return literal or non-returning
body requires the full semantic/effect checker and fails this preview.

This is **not** a general Oreslang function-body type checker or a runtime
execution authorization. It cannot establish generic substitution, borrow
lifetimes, effect safety, complete lexical scope, or an executable `Option<T>`
ABI. `readiness()` stays `SHAPE_ONLY` and `requireExecutable()` rejects.
The Java TypeChecker, OwnershipChecker and interpreter retain their separate
fail-closed staged-call gates. The added source JUnit methods do not
constitute a green executable test suite until real CI executes them.

The stacked PR was merged with the latest parent to pick up checks rejecting
class/interface/type-alias collisions in the parser-owned candidate preview.


## Caller-side scalar witness hardening (2026-10-18)

A scalar declaration/signature preview is not proof that its **caller** is
type-correct. In a narrow, linear same-module literal-call preview, issuance
must reject a non-void caller without an explicit reachable return, a bare
return from a non-void caller, a scalar return contradicting the caller's
declared result type, a value return from a void caller, and an unrelated
literal binding with contradictory declared type. The issuer also checks
caller and selected-callee formal-name uniqueness before making lexical
type claims. These checks are intentionally scoped to the literal-only
`CheckedScalarInvocationWitness`; ordinary recursive semantic/ownership
analysis remains required for all richer expression and control-flow forms.

The resulting witness remains `SHAPE_ONLY`. Neither a locally verified
callee body nor these caller checks establish complete effects, borrow/move
authority or the `Option<T>` runtime ABI. `requireExecutable()` continues
throwing; staged guest calls remain disabled. The committed source regressions
must not be called passing JUnit tests until a runner executes the exact tree.


## Concrete scalar-literal Option ABI preview (non-executable)

`CheckedLiteralOptionAbi.materialize(CheckedScalarInvocationWitness)`
materializes an independently checked **literal-only** source capture vector
and declaration-order typed argument vector. Its possible values are
`RequiredValue(CapturedScalar)`, `SomeValue(T, CapturedScalar)`, and
`NoneValue(T)`. The scalar source type is rechecked against the original
literal AST; no guest expression is executed. Named argument permutations
cannot permute callee ABI positions; omitted optional formals inject an
explicitly typed `None<T>` without allocating a source temporary.

The `PASS_OPTION` action is purposely **not** materialized by this preview:
a source spelling like `None` is not proof of a real `Option<T>` value.
It requires full nominal type and ownership analysis. Static module
functions only; the preview does not evaluate a guest method receiver.
`readiness()` remains `SHAPE_ONLY` and `requireExecutable()` fails.
Do **not** feed these Java records to the Oreslang runtime as if they were
verified guest ABI values. Full compiler-owned type/effect/borrow checking,
runtime representation compatibility, and executable tests are still needed.

Seven JUnit source tests cover positional ABI preservation under six named
permutations, explicit/implicit omission, supplied Some wrapping,
immutability, invalid source types and required omissions, and refusal of
unverified existing Option values. No actual repository JUnit pass is
established until a funded runner executes the exact Git tree.


## Non-finite scalar values require independent source/type proof

The narrow `OptionalCallLiteralStage.exactScalarLiteralType` AST oracle and
its downstream `CheckedLiteralOptionAbi` value envelopes accept Java `Double`
payloads as source `float` values **only when finite**. This is a
fail-closed source-origin restriction: an arbitrary Java caller can construct
`Ast.LiteralExpr(Double.NaN)` or either infinity without demonstrating a
corresponding Oreslang literal spelling or type/effect proof.

This is **not** a language-wide prohibition of IEEE-754 NaN/infinity results
from verified arithmetic or library operations. Those semantics require the
real compiler and runtime, not these literal-only previews. Finite values,
including `+0.0` and `-0.0`, remain admitted by the scalar oracle. The
`CheckedLiteralOptionAbi` and direct literal-call planner JUnit regressions
are committed but unexecuted pending JDK25 Maven Actions capacity.


## Full compiler-issued ordinary-call baseline (2026-10-18)

`VerifiedDirectModuleCall.analyzeAndIssue(source, module, caller)` is a
deliberately narrow **ordinary positional call** proof boundary. Unlike
`BoundInvocation` and `CheckedScalarInvocationWitness`, its private
constructor can only be reached after `OresCompiler.analyze` has run the
actual TypeChecker and OwnershipChecker against a freshly parsed source
program. It retains original checked AST identities for the unique same-module
function, calling function and immediate `Ast.CallExpr`. The issuer denies
imports, complex calling bodies, effectful or optional signatures, name
shadowing, ambiguous module/call-target namespaces, unproved recursion and
staged `StagedCallExpr`/`StagedNewExpr` source syntax.

This narrow identity is a baseline for integrating compiler-derived target
selection with runtime invocation. **It is not a generic authorization to
execute `T?` calls**, is not transferable to another program, and does not
replace trusted checked ownership/effects or the actual runtime Option ABI.
An ordinary `CallExpr` is already executable via the existing interpreter;
a new GraalVM regression uses identical source for full admission and
normal guest runtime execution, expecting output `42`. All seven source JUnit
methods remain unverified until the exact-head suite runs on a real runner.


### Direct-call proof excludes generic applications and spreads

The compiler-issued `VerifiedDirectModuleCall` baseline is intentionally
limited to a monomorphic, fixed-slot, positional `Ast.CallExpr`. Even after
the real TypeChecker and OwnershipChecker succeed, an explicit generic
application (`typeArgumentsPresent`, including an explicit empty marker)
and an argument `Ast.SpreadExpr` require *separate* source-to-formal
instantiation, pack-expansion, and ownership evidence.

`requirePlainPositionalCallShape` therefore rejects those AST shapes
before issuing the ordinary-call identity. A package-local JUnit source
suite covers normal positional arguments, explicit type applications and
spread/mixed argument items. This is a conservative limitation of this
proof, not a language-wide ban on generic calls or spreads. The staged
named/optional path remains non-executable.

A local **source-free** Java 21 stand-in smoke reproduced the guard and
passed 50,000 seeded generated shapes (6,206 admitted and 43,794 rejected).
It is not compiled JDK25 project source, Maven/JUnit, or a compiler runtime
conformance result. The exact-tree suite remains pending a working,
independently authorized private Actions destination.


## Experimental real-checker scalar optional-call rewrite (October 2026)

The **opt-in** `LiteralOptionalCallCompiler.lowerAndAnalyze(source, module, caller)`
API now bridges a narrow, original-AST named/optional call into the existing
ordinary `CallExpr` pipeline. Unlike shape previews, it constructs a fresh
`Ast.Program` whose selected `T?` formals are explicit `Option<T>`
formals, replacing each supplied scalar literal with exactly one `Some<T>`
call, each omitted optional slot with `None`, and required scalars with their
original literal ASTs. Slots are in formal order; literal inputs have no guest
side effects to reorder. The entire rewritten program is then submitted
to **both real front-end checkers** using `OresCompiler.analyze`; a
private-constructor result is returned only after success and source-binding
identity checks.

The experimental boundary rejects imported/multi-module programs, unverified
qualified or dynamic targets, constructors/methods/actors, nonliteral effects,
spreads, generics, annotations, borrowed/mutable formals, incorrect scalar
types, user-defined Some/None constructors, inferred non-scalar optional
payload types, and existing Option<T> values without full ownership proof.
It currently handles an **explicitly named or omitted staged call** in a
single narrow linear function body, including `name: _`. Ordinary
`greet()` without a staged source marker remains unsupported by this
experimental API.

**This does not enable general source-language optional calls.** The default
`OresCompiler`, TypeChecker, OwnershipChecker and direct runtime admission
remain fail-closed on original unlowered `T?`/`StagedCallExpr` source.
The verified result is an AST intended for future authenticated runtime
handoff, not a guest invocation. The JUnit methods include original
`return match`, omission, named-order and negative type cases; **they
have not passed** until actual Maven/GraalVM Actions execute on the exact
Git tree. Do not promote this into the default compiler until provenance,
effect order, nested expressions, receiver and async ownership are proven.


## Experimental implicit empty-call bridge (opt-in; not default execution)

`LiteralOptionalCallCompiler.lowerImplicitAndAnalyze(source, moduleName,
callerName)` is a **separate opt-in API**, not a relaxation of the default
parser, TypeChecker, OwnershipChecker, or runtime staged-call rejection. It
parses untrusted text fresh, requires one unimported undecorated module and an
ordinary caller, identifies exactly one immediate **original** `CallExpr` of
the form `greet()`, and rejects type application, supplied arguments,
overloaded targets, recursion, missing targets, and zero-formal/required-formal
targets. Every selected target formal must be `T?` with an exact scalar
`T` (int/float/bool/string).

Only after checking that source occurrence does this opt-in bridge create a
single intermediate `StagedCallExpr` with no supplied items; it then reuses
`SameModuleLiteralInvocation`, the checked formal-order ABI slot plan,
`Option<T>` substitution and `OresCompiler.analyze` (real TypeChecker
**and** OwnershipChecker). Thus a call with multiple optional formals yields
one independent typed `None` per formal, without an invented source
expression, missing required value or evaluation-order effect.

The existing `lowerAndAnalyze` API still requires an original staged
named/omitted call, and the normal production compiler still rejects
unlowered `T?` formals. The opt-in return object is checked **AST**, not a
guest-executable capability: no arbitrary source or AST can skip compilation,
and no public guest program automatically obtains this lowering yet. Target
body/return correctness still depends on both real checkers and needs actual
JDK25/JUnit and runtime test evidence before any integration.

The new JUnit source regressions cover successful `greet()`, multiple
optional parameters, explicit/default gate separation, and negatives for
required or zero-formal signatures, ambiguity, generic type application,
and nonempty calls. They are committed but **not claimed green** until a
funded runner executes the exact source tree.


## Literal-only Option ABI reference frame

The Java compiler now contains `OptionalCallScalarAbiFrame.from(CheckedScalarInvocationWitness)`: an intentionally **non-executable** semantic reference for same-module, literal-only staged calls. It constructs immutable, declaration-ordered records representing three distinct typed cases: `Required(T value)`, `Some(Option<T>, T value)`, and `None(Option<T>)`. For both `Some(0)` and `Some(false)` the payload is preserved; omission has no evaluated source index and is not encoded as null, zero or false. Each ABI slot retains the original raw call-item index, including explicit positional/named `omit` and `_`; implicit omissions have index `-1`.

Only the original checker-issued lexical and scalar-type witness can yield a complete `Frame`, whose constructor is private. This reference is deliberately narrower than execution: a supplied `Option<T>` (including `None` used as an expression), effectful expressions, borrowed values, imports, generics, methods, constructors, actors, and dynamic calls remain unsupported by the scalar frame. `Frame.requireExecutable()` throws, and the existing full compiler, ownership and Truffle runtime gates remain unchanged.

This is **backend conformance scaffolding**, not an Oreslang runtime `Some`/`None` implementation, not full `TypeChecker`/ownership validation, and not guest-language execution. `OptionalCallScalarAbiFrameTest` supplies seven JUnit regression cases; merge acceptance still requires actual JDK25 Maven/JUnit and GraalVM/LLVM backend runs from an approved runner.
