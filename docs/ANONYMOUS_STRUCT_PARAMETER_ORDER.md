# Anonymous inline struct parameter order: cross-target contract

**Status:** candidate language contract; implementations below are not yet all conformant.
Dependent Java structural typing integration: draft PR #1809. Regression tests: draft PR #1828.

## Two accepted spellings

```oreslang
fnc display(item: struct{name: string, age: int}): void {}
fnc display(struct{name: string, age: int} item): void {}
```

Both normalize to one typed parameter descriptor: name = `item`, type =
`struct{name: string, age: int}`, identical binding mutability, ownership,
shape, runtime representation, ABI, overload resolution, and diagnostics.
The formatter uses **type-first** canonically; name-first remains accepted.
Neither spelling represents a named call argument. Source locations should
remain accurate through normalization.

Java's parser has both branches in `parseParametersUntil`, while
`parseTypeAtom` handles `struct{...}` and `record{...}` in type positions.

## Important typing boundary

Anonymous inline record/struct parameter types must follow identical typechecker
semantics irrespective of parameter order **and irrespective of whether the
redundant structural modifier is present**. Their readonly width projection
must be identical; nominal types require explicit structural opt-in.
In Java's draft structural work, named class/interface/trait types are nominal
by default, while any **top-level anonymous inline `struct{...}`, `record{...}`,
or `{...}` parameter type** automatically requests a readonly structural
shape view. `structural struct{...} value` is equivalent to the bare form.
The keyword is still required for a named type to opt into structural matching.
It must never silently recover write authority or cross actor isolation.
Do not infer width compatibility merely because a parser accepts an inline
struct type.

## Backend-specific current status and gates

| Target | Verified current architecture | Required work |
| --- | --- | --- |
| Java/Graal | `Parser.java` accepts both orders and normalizes anonymous structural keywords; PR #1828 plus dependent implicit-shape PR cover the matrix | Exact-head Maven/JUnit and Graal guest execution, ownership/structural semantics and canonical formatter tests |
| LLVM | Native `frontend/Parser.cpp` takes type-first scalars/classes/generics, not full inline `struct{...}` IR | Implement both spellings and safe typed native record field ABI before reporting parity |
| WASM | Source -> pinned native LLVM compiler -> LLVM IR -> Clang wasm32 -> verified Wasm | Test both spellings through **real pinned LLVM compiler**, wasm32 runtime and bridge ABI |
| JS | Scalar-v1 `src/compiler.mjs` accepts `int x`; program-v2 is separate | Real shape/type support through checked JS IR, Node + browser; no token-only acceptance |
| .NET | Bootstrap CIL compiler only has checked arithmetic `main` subset | Extend source parser, typed IR and CIL/Native AOT record ABI; test Windows/Linux |

Tracking: LLVM issue #813, WASM issue #106, JS issue #30, .NET issue #101.

## Conformance tests and edge cases

1. Simple, empty, nested, generic (`Option<struct{...}>`) and quoted-key shapes.
2. Mixed name-first and type-first params in one function, and primitive parity.
3. Inline field colon is distinct from the name-first parameter colon and
   named-call argument colon; braces must not consume the function body.
4. Duplicate fields and parameter names, missing name/type, illegal delimiter
   combinations, and malformed or unclosed record shapes fail closed.
5. Implicit anonymous readonly width passing calls (with or without keyword), missing-field and wrong-field-type rejections; named contracts remain nominal without opt-in.
6. Implicit and explicit anonymous readonly `structural` width projection,
   rejection of `mut` in either spelling, write-through and cross-actor leakage.
   Named types still require the explicit modifier.
7. Normalized compiler AST/type checks before lowering; check no param-order
   branch survives into LLVM IR, Wasm ABI, JS IR, or CIL.
8. Formatter canonicalizes to type-first, then reformat is idempotent and
   canonical output reparses. Ensure diagnostics preserve original spans.
9. Real compiler/test execution at each **exact candidate commit**. Tests never
   become green when a workflow fails before running any job steps.

## Release gate

Do **not merge or declare cross-target parity** until each target has actually
compiled and executed the relevant positive cases and rejected the negative
cases. A source-free alternate-org GitHub Actions canary or a skipped runner
is not executable compiler evidence.
