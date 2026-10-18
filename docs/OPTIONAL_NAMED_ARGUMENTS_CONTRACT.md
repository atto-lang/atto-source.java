# Named and non-trailing optional value arguments: hardened language contract

Status: **design accepted for implementation planning, NOT currently available as source syntax**.
Java/GraalVM currently has an arity-based runtime, ordinary expression-list calls,
and exactly one explicitly declared class constructor. LLVM parity is separate.
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
a supplied ordinary `T` is wrapped in `Some(T)`, and omission is `None`.
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
- Named/optional calls must select a **unique** statically visible call
  shape based on supplied labels, required slots, and positional prefix.
  For example `f(int a)` together with `f(int a, bool? flag)` makes `f(1)`
  ambiguous: fail at compile time. Do **not** prefer exact arity, guess from
  types, or depend on declaration order to resolve optional ambiguity.
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
