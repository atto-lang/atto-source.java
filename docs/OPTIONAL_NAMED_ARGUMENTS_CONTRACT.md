# Named and non-trailing optional value arguments: hardened language contract

Status: **design accepted for implementation planning, NOT currently available as source syntax**.
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
`T` nullable, or introduce JavaScript's `undefined`. This is intentionally
different from `Option<T> name`, a *required* parameter whose caller must
supply an Option value. In the callee, `T? name` is read as an `Option<T>`;
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
