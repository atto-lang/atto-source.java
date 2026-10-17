# Boolean logic contract (v1, 2026-10-17)

## Infix operators

- `and` is a reserved, case-sensitive, short-circuit alias for `&&`.
- `or` is a reserved, case-sensitive, short-circuit alias for `||`.
- Both use precisely the same AST operators (`&&` and `||`), Boolean-only type checking, evaluation order, associativity and definite-initialization treatment as their symbolic spellings.
- Evaluation proceeds left to right; `and` does not evaluate its right expression when its left side is false, and `or` does not evaluate its right expression when its left side is true.
- There is **no implicit truthiness** and no implicit integer-to-Boolean conversion. `and` and `or` are not callable names. Capitalized `And` and `Or` remain distinct builtin functions.
- Precedence, high to low: comparison/equality, bitwise `&`, bitwise `^`, bitwise `|`, logical `and`/`&&`, logical `^^`, logical `or`/`||`, ternary `?:`, assignment `=`. Operators of the same precedence are left-associative. Mixing word and symbol spellings must not change meaning.
- After `.`, `and` and `or` can still be member names; declaring bindings or callable names `and` or `or` is invalid, consistent with `eq`/`neq`.

## Boolean intrinsics

| Callable | Scalar arguments | Array/list overload | Evaluation |
| --- | --- | --- | --- |
| `And` / `BooleanOps.And` | 2 or more `bool` | one `Array<bool>` or list; empty reduces to `true` | scalar arguments left-to-right with short-circuit |
| `Or` / `BooleanOps.Or` | 2 or more `bool` | one `Array<bool>` or list; empty reduces to `false` | scalar arguments left-to-right with short-circuit |
| `Xor` / `BooleanOps.Xor` | **exactly 2 `bool`** | one `Array<bool>` or list; existing parity reduction retained for compatibility | both scalar arguments evaluated exactly once, in order; no short-circuit |

The legacy `Xor(array)` reducer computes odd parity for any array length, including empty (`false`). It is a *distinct one-argument aggregate overload*; it does not authorize `Xor(a, b, c)`. A future migration may introduce an explicit `XorParity`/parity reducer before retiring that aggregate overload; do not silently change its result, and do not remove it without an API migration plan.

Calls have no generic call-site type parameters. Scalar parameters must type-check as `bool`; array/list contents must type-check as `bool`. Namespace qualification does not change built-in semantics. User-defined callables with the same name or a user-defined `BooleanOps` module retain normal shadowing/overloading rules, rather than being rewritten as intrinsics.

### Scalar lowering model

Scalar builtin calls are not ordinary eager Oreslang function calls after type checking. The type checker records the **exact AST call identities** that resolved to the builtin family, including shadowing decisions, and the front end then rewrites them before ownership checking/execution:

```text
And(a, b, c) -> (a && b) && c
Or(a, b, c)  -> (a || b) || c
Xor(a, b)    -> a ^^ b
```

This is required for semantic correctness. A source implementation such as
`fnc Or(bool a, bool b, ...rest)` would receive already-evaluated arguments in
an ordinary eager call, so it could not preserve the established rule that
later scalar operands are skipped after a decisive value. General user-defined
variadic parameters are also not part of the current callable grammar.

Lowering occurs **after type/name resolution** so a source-defined `And`, `Or`,
or `Xor`, and a real source module named `BooleanOps`, are never accidentally
rewritten. The evaluator is no longer permitted to execute scalar builtin calls
directly; an unlowered scalar call is an internal compiler error.

The one-argument aggregate reducers remain calls for now. They can move to
Oreslang-owned core source once packaged core-module resolution and backend
linking can make the same implementation available to JIT, Native Image and
LLVM without introducing a second semantic owner.

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

## Backend conformance gates

1. Lexer distinguishes `and` and `or` from identifiers and from `And`/`Or`.
2. Parse both spellings to identical short-circuit AST operations. Check ternary, nested calls, operators without whitespace, type errors, precedence, and side-effect ordering.
3. Enforce scalar XOR exact arity in the type checker, then prove scalar `And`/`Or`/`Xor` calls lower to ordinary logical AST before ownership checking/execution; test zero, one, two, three and more arguments.
4. Fail closed if an unlowered scalar Boolean intrinsic reaches the evaluator. Preserve existing aggregate reducer behavior while the compatibility API is present.
5. Run the same fixtures on Java/Graal JIT, JVM AOT/native-image and LLVM when LLVM has Boolean expression and builtin-call lowering. **LLVM's current restricted signed-i64 frontend does not implement this Boolean surface**: it must fail closed until typed Boolean IR, short-circuit CFG lowering, and parity dispatch are implemented.
6. Match test evidence to the exact Git tree/SHA, not to an earlier green run. A no-runner/no-steps Actions failure is not evidence of compiler failure.

## Adjacent core/stdlib hardening follow-ups

This change audits the Boolean intrinsic slice only; it does not purport to complete a whole-stdlib implementation audit. The larger core-library audit in `docs/CORE_NATIVE_STDLIB_AUDIT_2026-10-09.md` still identifies incomplete packaged native module resolution, typed ABI/intrinsic registration, capability boundaries, portable Option/Result/stream contracts, and parity validation. Keep host Java glue separate from Oreslang-owned policy, preserve actor ownership checks, and add CI conformance vectors when migrating a function from Java to `.ores`.
