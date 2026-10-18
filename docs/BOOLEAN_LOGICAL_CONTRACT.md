# Boolean logic contract (v1, 2026-10-17)

## Infix operators

- `and` is a reserved, case-sensitive, short-circuit alias for `&&`.
- `or` is a reserved, case-sensitive, short-circuit alias for `||`.
- `xor` is a reserved, case-sensitive alias for Boolean `^^`. It evaluates both operands exactly once, left to right; unlike `and` / `or`, it does not short-circuit.
- All three word operators use their corresponding symbolic AST operators (`&&`, `||`, and `^^`), with the same Boolean-only typing, evaluation order, associativity and control-flow treatment as the symbolic spellings.
- Evaluation proceeds left to right; `and` does not evaluate its right expression when its left side is false, and `or` does not evaluate its right expression when its left side is true.
- There is **no implicit truthiness** and no implicit integer-to-Boolean conversion. `and`, `or`, and `xor` are not callable names. Capitalized `And`, `Or`, and `Xor` remain distinct callable builtin families.
- Precedence, high to low: comparison/equality, bitwise `&`, bitwise `^`, bitwise `|`, logical `and`/`&&`, logical `xor`/`^^`, logical `or`/`||`, ternary `?:`, assignment `=`. Operators of the same precedence are left-associative. Mixing word and symbol spellings must not change meaning.
- After `.`, `and`, `or`, and `xor` can still be member names; declaring bindings or callable names `and`, `or`, or `xor` is invalid, consistent with `eq`/`neq`.

## Boolean intrinsics

| Callable | Scalar arguments | Array/list overload | Evaluation |
| --- | --- | --- | --- |
| `And` / `BooleanOps.And` | 2 or more `bool` | one `Array<bool>` or list; empty reduces to `true` | scalar arguments left-to-right with short-circuit |
| `Or` / `BooleanOps.Or` | 2 or more `bool` | one `Array<bool>` or list; empty reduces to `false` | scalar arguments left-to-right with short-circuit |
| `Xor` / `BooleanOps.Xor` | **exactly 2 `bool`** | one `Array<bool>` or list; existing parity reduction retained for compatibility | both scalar arguments evaluated exactly once, in order; no short-circuit |

The legacy `Xor(array)` reducer computes odd parity for any array length, including empty (`false`). It is a *distinct one-argument aggregate overload*; it does not authorize `Xor(a, b, c)`. The packaged Oreslang `std/core` module owns `all_bool(Array<bool>)`, `any_bool(Array<bool>)`, and `xor_parity_bool(Array<bool>)` reducer loops. On the Java backend, public one-argument builtins eagerly materialize their aggregate argument and then invoke those packaged source reducers through the reserved `@std/core.ores` linked-code-unit identity. There is no Java reducer fold and no implicit guest-visible `core` binding. Reuse of an existing `@std/core.ores` unit is content-attested against the fully analyzed compiler-packaged core AST, so a guest cannot spoof the trusted route by choosing the reserved source name. A future migration may introduce an explicit `XorParity`/parity reducer before retiring that aggregate overload; do not silently change its result, and do not remove it without an API migration plan.

Calls have no generic call-site type parameters. Scalar parameters must type-check as `bool`; array/list contents must type-check as `bool`. Qualification through the trusted builtin `BooleanOps` namespace preserves intrinsic behavior; qualified `std/core` source-module calls are ordinary eager calls. User-defined callables with the same name or a user-defined `BooleanOps` module retain normal shadowing/overloading rules, rather than being rewritten as intrinsics.

### Scalar lowering model

Scalar builtin calls are not ordinary eager Oreslang function calls after type checking. The type checker records the **exact AST call identities** that resolved to the builtin family, including shadowing decisions, and the front end then rewrites them before ownership checking/execution:

```text
And(a, b, c) -> (a && b) && c
Or(a, b, c)  -> (a || b) || c
Xor(a, b)    -> a ^^ b
```

This is required for semantic correctness. A source implementation such as
`fnc Or(bool a, bool b, rest ...bool)` receives already-evaluated arguments in
an ordinary eager call, so it could not preserve the established rule that
later scalar operands are skipped after a decisive value. General user-defined
variadic parameters use terminal `rest ...Type` on synchronous source callables; this source-call ABI does not grant laziness.

Lowering occurs **after type/name resolution** so a source-defined `And`, `Or`,
or `Xor`, and a real source module named `BooleanOps`, are never accidentally
rewritten. The evaluator is no longer permitted to execute scalar builtin calls
directly; an unlowered scalar call is an internal compiler error.

The one-argument aggregate reducers remain calls because aggregate construction
is eager. Java/Graal routes those calls to compiler-owned packaged `std/core`
source after the argument is materialized. LLVM still needs its native
`Array<bool>` representation and reducer-loop lowering; it must preserve the
same eager-container boundary without introducing a second semantic definition.

## Example

```ores
pub fnc main(): void {
  stdio.println(true and false or true);               // true
  stdio.println(true && false || true);                 // true
  stdio.println(And(true, true, true));                 // true
  stdio.println(Or(Xor(true, And(true, true, true)), true)); // true
  stdio.println(false and [true][99]);                 // false; no invalid index access
}
```

## Public callable overloads and terminal rest arguments

The standard-library surface is a real overload family selected by caller-visible
argument count, not parameter type and not return type:

```ores
pub fnc Or(List<bool> values): bool { return any_bool(values); }
pub fnc Or(bool a, bool b, rest ...bool): bool {
  if a or b then return true; fi
  for const value of rest do
    if value then return true; fi
  done
  return false;
}
```

`And` has the same pair of overloads. `Xor` retains the binary scalar
overload and the unary `List<bool>` parity overload. For `Or/And`, arity 1
selects the collection overload and arity >=2 selects the scalar rest fallback.
Arity 0 is rejected. A fixed exact-arity overload, if declared, wins over the
single rest fallback. Each family may declare at most one rest fallback.

The final formal `rest ...T` collects zero or more trailing **already evaluated**
arguments into a fresh owned `List<T>` in the function body. This is a general
source-callable argument feature, separate from trusted builtin lowering.
Ordinary calls, including `core.Or(a,b,c)`, eagerly evaluate arguments before
entering the function; the unqualified compiler-owned `Or(a,b,c)` builtin
lowers to a short-circuit AST instead. Never conflate those contracts.
Current rest implementation is for synchronous, non-generic, non-actor
functions/routines; methods, interface methods, constructors, async,
generators, and effectful rest callables remain fail-closed pending ABI,
scheduling and ownership hardening. Reifying a rest callable as a fixed-signature
function value is rejected pending a typed variadic callable ABI.

## Lowering architecture

Scalar calls do not need variadic source functions. After name resolution and type checking:

- `And(a, b, c, ...)` lowers to a left-associated `&&` tree.
- `Or(a, b, c, ...)` lowers to a left-associated `||` tree.
- `Xor(a, b)` lowers to `a ^^ b`.
- Scalar `Xor` with any arity other than two is rejected before lowering.
- User-defined functions/modules named `And`, `Or`, `Xor`, or `BooleanOps` are never rewritten.
- Aggregate one-argument calls stay as calls. Java/Graal routes them through the reserved packaged `@std/core.ores` evaluator after eager aggregate materialization, so user modules cannot shadow the reducer implementation. LLVM aggregate lowering remains a backend follow-up.

The synchronous source `rest ...Type` ABI is independent of trusted Boolean scalar intrinsic lowering. Neither the variadic fallback nor the builtin rewrite changes the rule that fixed overload identity is caller-visible name + exact arity.

### Arity-selected source/API signatures

The public callable family is modeled as distinct overload slots, **not** runtime dispatch by argument types:

```ores
pub fnc Or(List<bool> values): bool { ... }
pub fnc Or(bool a, bool b, rest ...bool): bool { ... }

pub fnc And(List<bool> values): bool { ... }
pub fnc And(bool a, bool b, rest ...bool): bool { ... }

pub fnc Xor(List<bool> values): bool { ... }
pub fnc Xor(bool a, bool b): bool { ... }
```

The `rest ...bool` line above is the **intended public signature notation**, not a claim that general source-defined variadic callables have already been implemented. The current compiler executes the scalar 2+ `And/Or` family through call-site AST lowering; the single-list source overloads now exist in packaged `std/core.ores`. Defining a user `Or(List<bool>)` at arity one does not hide builtin `Or(bool,bool,...)` calls at other arities; a user-defined function at the **exact requested arity** takes precedence. A user-defined module named `BooleanOps` shadows that builtin namespace as a whole.

A general rest-parameter implementation must reserve a distinct **arity range** `[minArity, infinity)`, reject overlapping declared signatures (including a fixed arity within the range), bind rest values to a readonly `List<T>`, and implement consistent selection in the type checker, interpreter, AOT, callable values, and linked import ABI. The virtual/intrinsic builtins already accept these arities but must not be mistaken for ordinary eager variadic functions: scalar `And`/`Or` short-circuit by compiler lowering, whereas ordinary Oreslang calls eagerly evaluate all actual arguments. No special variadic grammar or user-function execution is introduced before those conformance gates pass.

## Backend conformance gates

1. Lexer distinguishes `and` and `or` from identifiers and from `And`/`Or`.
2. Parse both spellings to identical short-circuit AST operations. Check ternary, nested calls, operators without whitespace, type errors, precedence, and side-effect ordering.
3. Enforce scalar XOR exact arity in the type checker, then prove scalar `And`/`Or`/`Xor` calls lower to ordinary logical AST before ownership checking/execution; test zero, one, two, three and more arguments.
4. Fail closed if an unlowered scalar Boolean intrinsic reaches the evaluator. Preserve existing aggregate reducer behavior while the compatibility API is present.
5. Run the same fixtures on Java/Graal JIT, JVM AOT/native-image and LLVM when LLVM has Boolean expression and builtin-call lowering. **LLVM's current restricted signed-i64 frontend does not implement this Boolean surface**: it must fail closed until typed Boolean IR, short-circuit CFG lowering, and parity dispatch are implemented.
6. Match test evidence to the exact Git tree/SHA, not to an earlier green run. A no-runner/no-steps Actions failure is not evidence of compiler failure.

## Adjacent core/stdlib hardening follow-ups

This change audits the Boolean intrinsic slice only; it does not purport to complete a whole-stdlib implementation audit. The larger core-library audit in `docs/CORE_NATIVE_STDLIB_AUDIT_2026-10-09.md` still identifies incomplete packaged native module resolution, typed ABI/intrinsic registration, capability boundaries, portable Option/Result/stream contracts, and parity validation. Keep host Java glue separate from Oreslang-owned policy, preserve actor ownership checks, and add CI conformance vectors when migrating a function from Java to `.ores`.
