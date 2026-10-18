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
a declaration may have at most one `rest ...T items` parameter and it must
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
pub fnc log(string? prefix, rest ...string messages): void { /* ... */ }

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
`f(int x, rest ...int tail)`. Overloads must never resolve by trying
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
