# Named value arguments and named type metadata: pre-1.0 contract

Status: **proposed contract / implementation not yet enabled**. This is an audit artifact, not a claim that the compiler accepts named value arguments.

## Canonical delimiters and surfaces

Oreslang deliberately uses two different argument channels:

- Type reference/compile-time metadata: `Type<messages=FooMessages>` (`=`).
- Runtime value argument: `foo(bar: 5, a: true)` (`:`).
- Callable *declarations* retain type-first parameters: `fnc foo(int bar, bool a): int`.

These syntaxes must remain distinct in lexer, parser, AST, semantic analysis, formatter, and Java/GraalVM and LLVM lowerings. `foo(bar = 5)` is not a named argument; ordinary assignment expressions must not be silently rewritten into labels.

Example:

```oreslang
pub fnc foo(int bar, bool a): int {
    return a ? bar : 0;
}

pub routine main(): void {
    const x = foo(5, true);
    const y = foo(bar: 5, a: true);
    const z = foo(a: true, bar: 6);
    const w = foo(7, a: true);
}
```

Calls are statically bound to `foo/2` and yield 5, 5, 6, 7 respectively.

## Binding algorithm

A compiler binds a call against its *statically resolved callable declaration*:

1. Select the callable by existing namespace/owner, name and arity rules. Class method overload selectors are **(kind, name, arity)** and must **not** incorporate labels or argument runtime types.
2. Evaluate the callee/receiver (as applicable), then each argument expression **exactly once in lexical source order** into independently tracked temporaries. Preserve existing ownership, move/borrow, effect, suspension, cancellation and cleanup semantics in that order.
3. Bind positional arguments to the leading unclaimed parameter slots. After the first named argument, all remaining arguments must be named **except** for the narrowly defined final anonymous trailing-closure rule below.
4. Bind each `name: expression` by exact, case-sensitive declared parameter name to one unclaimed slot.
5. Reject unknown names, duplicates, any slot filled twice (whether positionally or by name), missing required slots, excess arguments and wrong argument types at compile time.
6. After checking, pass the evaluated temporaries to the existing ABI in **declaration parameter order**. No runtime dictionary/hash map lookup, runtime label reflection, or parameter-name overload dispatch.

An implementation MUST NOT re-sort the original expressions before evaluation: `foo(a: effect1(), bar: effect2())` evaluates `effect1` before `effect2`, even though `bar` is the first formal parameter. Ownership checking is performed on that same lexical evaluation order, with each argument's applicable formal parameter mode known from binding.

## Final anonymous trailing-closure exception

A call may contain **exactly one** unlabeled final anonymous closure after named value arguments:

```oreslang
compute(
    region: motion,
    writes: [Motion.x, Motion.y],
    fnc(chunk) { update(chunk); }
)
```

The accepted syntactic closure forms are `fnc(...) { ... }`, `|...| -> { ... }`,
and their `nlex` variants. This exception is not general positional-after-named
support. Before execution, static binding MUST prove that:

- the closure is the final source call item;
- exactly one formal slot remains unclaimed;
- that slot is the declaration's final formal;
- the final formal has a statically known callable type compatible with the closure;
- there is no spread, omission, second trailing closure, dynamic/opaque callable,
  label-only overload distinction, or runtime label lookup involved.

The AST retains an explicit trailing-closure origin bit so later lowering cannot
mistake this item for an ordinary positional argument. Its expression is still
evaluated exactly once in lexical source order. If any proof is unavailable the
call is rejected; callers can always use an explicit label such as
`body: fnc(chunk) { ... }`.

## Scope and compatibility

- Target call sites: top-level functions/routines, class instance/static methods, and class constructors. Calls on statically known callable references may be enabled once their signatures carry stable parameter labels.
- Callables without statically available parameter names (including unlabelled function-value types, dynamically resolved foreign calls and opaque builtins) MUST reject named arguments rather than silently treating them as positional.
- A class override / interface implementation must retain each inherited callable parameter label at the same ordinal position. Label changes cannot affect the ABI slot; they ARE source-compatibility changes for callers using names.
- Actor messages, record/object field literals, `self.forward()`/`self.backward()` transport semantics, and type metadata are **not** rewritten as named function calls.
- Current `...tuple` call spreads retain their existing semantics. Initial named-argument support MUST reject *mixing* spreads with named arguments unless a separately verified static tuple-expansion and source-order contract is implemented.
- Default runtime parameter values are **not** introduced by this feature. Every required slot must be supplied. Do not overload the separate `=` type-metadata syntax.
- Named arguments never change `CallableSelector` arity-only dispatch, including for overrides and static methods.

## Declaration vs. type-reference distinction: important current gap

As audited in the Java parser:

- `parseTypeAtom` accepts `<name=metaValue>` on **type references**, represented via `Ast.TypeRef.namedParameter(...)`, and canonicalizes these metadata keys.
- `parseGenericParameters`, used by **actor/class/function declarations**, currently accepts only bare `IDENT` names. It does **not** accept `define actor Foo<messages=FooMessages> as ... end`.
- `docs/grammar.ebnf` likewise distinguishes `generic_params = "<" IDENT ... ">"` from `named_type_param = IDENT "=" meta_value`.

Before enabling the exact declaration syntax, define whether `messages=FooMessages` means **fixed actor-header compile-time metadata** or **a default generic type parameter**. These are different features. Do not auto-interpret actor metadata as generic default binding, and do not claim type-reference metadata support implies declaration support. If actor-header metadata is selected, give it a distinct AST field and explicit type validation, including whether `messages` is reserved and which type kinds it accepts.

## Proposed grammar delta

```ebnf
call_argument      = spread_argument | named_value_argument | expression ;
named_value_argument = IDENT ":" expression ;
spread_argument    = "..." expression ;
call_argument_list = call_argument { "," call_argument } ;
named_type_param   = IDENT "=" meta_value ;   (* unchanged *)
```

The `IDENT ":"` recognition applies **only at the top level of a call argument item**. Conditional expressions, object literals, casts and label-like constructs inside expressions must not be misclassified. Named `new Type(...)` constructor calls need the same value-argument parser as `CallExpr`. Keep original source order and source locations in AST rather than encoding a named argument as an ordinary expression or converting it into a map.

## Required regression matrix (both backends where supported)

**Accept, run, and assert correct result:**

- `foo(5, true)`, `foo(bar: 5, a: true)`, `foo(a: true, bar: 6)`, `foo(7, a: true)`.
- Instance method, static method, constructor; inherited/overridden method with stable names; namespace-qualified callable.
- Function call in a nested expression; ternary in a named value, e.g. `foo(bar: flag ? 1 : 2, a: true)`.
- Observable effect order: `foo(a: effect1(), bar: effect2())` evaluates 1 then 2 and only once each, yet binds to formal order.
- Moves/borrows: no evaluation-order or ownership loophole; cleanup and cancellation remain correct on guest failure.
- Formatter is idempotent, emits `bar: 5` and preserves source argument order; preserves `<messages=FooMessages>` independently.
- Java interpreter, GraalVM Native Image AOT, hybrid image, LLVM native -O0/-O2 agree on outcomes and negative diagnostics.

**Reject at compile time (no silent fallback):**

- `foo(bar: 5, bar: 6)` (duplicate).
- `foo(5, bar: 6)` (positional/named collision).
- `foo(a: true)` (missing `bar`).
- `foo(unknown: 5, a: true)` (unknown name).
- `foo(a: true, 5)` (positional after named).
- `foo(a: true, bar: 5, extra: 7)` (extra).
- Named value call with `=` (`foo(bar=5)` must not be interpreted as named binding).
- Two same-name/same-arity method declarations differentiated only by parameter labels.
- Override with changed labels.
- Named arguments on unlabeled/dynamic callables, and named-plus-spread calls pending proven support.
- Any actor-header `<messages=...>` declaration until its separate static metadata semantics and parser have been implemented.

## Implementation order

1. Add first-class `CallArgument(label?, expression, spread, sourceSpan)` representation, preserving original order, to Java and LLVM frontends; ensure all walkers account for it.
2. Add one shared semantic named-call binder **per backend** that emits a checked call plan/slot permutation before type/effect/ownership checks and lowering; do not reorder raw AST expressions.
3. Integrate call plans in Java interpreter/suspendable execution and AOT; cover normal, tail, async, method, static and constructor calls.
4. Integrate LLVM frontend/native lowering with the identical semantic tests.
5. Update grammar, language docs and oreslang-format; keep format-only transformations semantics-preserving.
6. Enable CI parity and negative tests on exact Git trees before treating the feature as supported.

**No runtime named-argument reflection or nominal-type overload matching is required.** This is compile-time sugar with strict contract checking.
