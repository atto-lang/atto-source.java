# Named value arguments and named type metadata: pre-1.0 contract

Status: **general named/optional value arguments remain proposed**. Draft Java PR #1335 contains a first, unverified executable subset for direct local non-generic function/routine calls with primitive scalar literal arguments, all required and supplied. This is not merged or confirmed by full project CI. LLVM and other surfaces remain gated.

The staged next-mode proposal additionally makes a proven
`Option<T>` formal omittable by default, with `required Option<T>` for an
explicit-presence contract. See
[Option formal omission v2](../contracts/v2/OPTION_FORMAL_OMISSION_V2.md).
This behavior is not yet enabled in guest code.

See [OPTIONAL_NAMED_ARGUMENTS_CONTRACT.md](OPTIONAL_NAMED_ARGUMENTS_CONTRACT.md)
for proposed non-trailing `T? parameter` slots and constructor omissions.
The shape binder is a pre-integration utility; guest syntax remains gated.

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
3. Bind positional arguments to the leading unclaimed parameter slots. After the first named argument, all remaining arguments must be named.
4. Bind each `name: expression` by exact, case-sensitive declared parameter name to one unclaimed slot.
5. Reject unknown names, duplicates, any slot filled twice (whether positionally or by name), missing required slots, excess arguments and wrong argument types at compile time.
6. After checking, pass the evaluated temporaries to the existing ABI in **declaration parameter order**. No runtime dictionary/hash map lookup, runtime label reflection, or parameter-name overload dispatch.

An implementation MUST NOT re-sort the original expressions before evaluation: `foo(a: effect1(), bar: effect2())` evaluates `effect1` before `effect2`, even though `bar` is the first formal parameter. Ownership checking is performed on that same lexical evaluation order, with each argument's applicable formal parameter mode known from binding.

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

## Staged Java scalar-literal milestone (2026-10-19)

Only the Java draft compiler branch currently attempts runtime lowering for:
- Direct, non-generic, non-actor, non-suspending, non-generator, non-trapped local `fnc`/`routine` targets with no signature annotations
- Primitive literal arguments (`int`, `float`, `bool`, `string`) only; every formal supplied
- Positional prefix followed by named arguments, or all named arguments, bound by exact declared labels and full declared arity

The parser retains `Ast.StagedCallExpr`. Before execution the preflight, deferred-cleanup checker, type checker, ownership checker, and runtime independently admit this narrow case and normalize it to the ordinary `Ast.CallExpr`. Reordering raw expressions is permitted ONLY because scalar literals have no effects, move-only ownership or suspension. Local shadowing or an unresolved declaration is a hard error.

This is a bridge, not full named calls: nonliteral/effectful expressions, `None` as an expression, omitted `_`/`omit`, `T?` declarations, `@Default`, class methods and constructors, actors, variadic/rest parameters, imports/generics and dynamic targets remain disabled. Future support MUST retain source-order evaluated temporaries, checked nominal Option values, correct overload identity and ownership/effect guarantees; it may not generalize the literal-only permutation shortcut.

Compilation and guest-execution tests in `DirectNamedLiteralCallExecutionTest` are committed but not confirmed on the full project's JDK 25 Maven/JUnit CI: upstream GitHub Actions continues to fail before recorded steps. Independent source-free funded-runner tests are not compiler execution evidence.

## Source-order dynamic named-call milestone (2026-10-31, draft)

A subsequent unverified compiler-path change on Java draft PR #1335 admits
**nonliteral expressions only when the statically selected full-arity named-call
binding is an identity permutation** (source argument index equals formal index
at every slot). The expressions then lower directly to an ordinary CallExpr in
their original lexical order, reusing the existing type, ownership, effect,
deferred-cleanup and runtime call paths. This extends the previous primitive
literal-only subset to ordinary nested function calls and scalar variable reads
with named labels in declaration order; the new JUnit guest fixture checks
observable left-to-right effects.

This is deliberately *not* general named argument reordering. Any nonliteral
source item in a nonidentity permutation rejects before guest execution,
even if another item is a primitive literal. The only reordered calls still
admitted are the existing independently typed primitive literals. Only direct
local non-generic non-actor fnc targets with checked primitive scalar formals,
all full-arity and supplied, are eligible. Option/None lifting, omission,
defaults, methods, constructors, async, spread, generic and nominal modes
remain unsupported by this executable slice. The source-order candidate
predicate is only a preflight filter; actual admission rebinds the exact
selected declaration and validates the permutation before lowering.

**Evidence:** authored JUnit and source changes have been committed. No exact-head
JDK 25/Maven compiler or GraalVM Native Image execution has been observed at
the time of this addition. No Java/LLVM parity or production readiness claim
is justified by this staging change alone.

## Checked source-order closed scalar permutation milestone (2026-10-31)

Java draft PR #1335 now contains a further **unverified compiler/runtime**
extension for out-of-order named arguments. It does NOT admit arbitrary
effectful expressions: only full-arity direct local function/routine calls
with previously checked primitive Copy formals, whose original source items
are primitive literals or **closed direct zero-argument function calls**, are
eligible. A nested zero-argument function may have observable effects, but
does not transfer a loan or move-only argument into its enclosing invocation.
Locally shadowed/dynamic nested callbacks are separately forbidden by
TypeChecker, OwnershipChecker and runtime. The existing restrictions on
`@Default`, `T?`, Option/None, borrowed/mutable parameters, suspension,
generics, constructors, actor calls, spread and rest remain in force.

Implementation gate:

1. Resolve the exact outer callable with the existing lexical name+arity
   selection; reject ambiguous and shadowed names and unsupported signature
   effects. Build the immutable checked lexical-source-to-formal plan.
2. Compile-time checking may use a **formal-order CallExpr view** because
   all permuted payloads are primitive Copy scalars and nonliteral expressions
   are closed zero-argument calls. This is NOT an executable argument order;
   other expressions, captured callbacks, borrowed and move-only values
   remain rejected without a source-order ownership/effects proof.
3. At runtime, validate the same signature and plan **before any guest
   effects**, check class/actor lexical import rules, then evaluate original
   `StagedCallExpr.arguments` from left to right exactly once. Write each
   value directly to its already selected formal slot using the plan's
   inverse scalar mapping. Invoke the ordinary declared-arity function
   trampoline with these evaluated scalar values. If an earlier expression
   fails, later source expressions do not run.
4. A single formal-argument ArrayList is used rather than two source/formal
   temporary buffers. This is NOT an allocation-free Java call or a verified
   near-zero-GC performance guarantee; allocation profiling remains needed.

Example guest semantic witness:

```oreslang
pub fnc first(): int { stdio.stdout.write("A"); return 4; }
pub fnc second(): int { stdio.stdout.write("B"); return 3; }
pub fnc combine(int left, int right): int { return left * 10 + right; }
pub routine main(): void {
    stdio.stdout.write(combine(right: second(), left: first()));
    return;
}
```

Expected observable output: `BA43` (lexical effects B, A; formal-ABI
result 43). A three-argument case expects `BCA423`. Wrong primitive
return types, local shadowed callbacks and argument-bearing nested calls
must fail before runtime. Both the pure-plan and guest JUnit cases have
been authored; **no exact-tree JDK 25/JUnit or native-image run has
executed successfully**. Do not treat this stage as full effectful named
arguments or cross-backend parity, and keep the PR draft.
