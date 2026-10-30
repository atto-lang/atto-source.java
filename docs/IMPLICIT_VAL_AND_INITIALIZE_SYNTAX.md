# Immutable-by-default class fields and typed initializer syntax

Status: **proposed, unmerged Java parser implementation**. This document describes the intended language contract; native and formatter parity are separate release gates.

## Class fields

```oreslang
pub define class Target as
  pub String method;
  pub String authority;
  pub String path;
  pub bool tunnel;
end
```

Within a class, `Type name;` and `val Type name;` must construct the **same** `FieldDecl`: `BindingKind.VAL` with identical referent mutability. The same rule applies to `Type name: initializer;` and an explicitly `val`-bound field. `let`, `const`, and `final` remain explicit. `final` is currently a contextual class-field spelling lowered to VAL without mutable-referent access unless `mut` is present; it is **not** an independent fourth `BindingKind`.

**Important:** Java currently defines `val` as a fixed slot **with a mutable referent** (a `const mut` equivalent), not a deeply immutable object. Defaulting to `val` must not be described as a deep-freeze or as a sandbox boundary. The ownership checker, actor sealed-state rules and borrow capabilities still apply.

The old Java spelling `Type field: initializer` previously created a `LET` binding. This change intentionally makes it `VAL`. Source that reassigns a member must migrate to `let Type field: initializer;`. Do not auto-rewrite these declarations without checking member writes and alias/borrow effects. `@FromJson` name-first generated fields have separate existing behavior, and require a dedicated audit before changing that compatibility path.

Uninitialized fixed fields require complete single-write construction and a no-`self`-escape guarantee. A field-level `final` cannot be assigned twice; a `const` requires a declaration-time constant per existing rules.

A child class with an explicit `initialize(...)`/`constructor(...)` cannot fill an inherited uninitialized fixed slot. The constructor write exception applies only to fixed fields declared by that same class. Because explicit super-constructor chaining is not yet supported, the checker rejects an explicit child initializer if it inherits a fixed field without its own declaration-site initializer. The synthesized positional constructor route retains its separately validated field admission rules.

## Initializer and callable body surface

The preferred spelling is:

```oreslang
define class Connection as
  final int connection_id;

  initialize(int id) -> void ::
    self.connection_id = id;
  end
end
```

All four forms must parse to the same `ConstructorDecl` body, so runtime object construction calls the initializer exactly as a legacy `constructor(...)` would:

```oreslang
initialize(int id) -> void ::
  self.connection_id = id;
end

initialize(int id) -> void
  self.connection_id = id;
end

initialize(int id): void
  self.connection_id = id;
end

initialize(int id): void {
  self.connection_id = id;
}
```

The `initialize` keyword is contextual at class-member callable position, must declare `void`, and must not coexist with another `initialize` or legacy `constructor` in the same class. Existing `constructor(...)` remains a compatibility spelling until an explicit migration policy removes it; it does not gain a return-type clause in this phase.

The token `as` is **not** a valid executable callable body delimiter. Preserve `as` in class/actor/module headers and other grammatical roles. For ordinary named methods, typed `:: ... end`, typed newline `... end`, and braced bodies are parser forms. The intended formatter canonical form is `name(...) -> ReturnType :: ... end`. The canonicalizer must not merely replace tokens in comments/strings and must round-trip through an accepted compiler grammar.

## Release checklist

- [ ] Java parser + JUnit positive/negative cases, including four initializer shapes, AST equivalence and semantic type checking
- [ ] Java full suite and constructor definite-initialization/escape safety; migration diagnostics for formerly implicit-LET writes
- [ ] LLVM frontend + native execution and error parity on the exact fixtures
- [ ] .NET, WASM and Forge parser or shared-source admission parity, with tests proving each actually executes source
- [ ] oreslang-format canonicalizes explicit class VAL away and four initializer shapes to one form, preserving locals and comments
- [ ] Independent private CI runner admission verified by executed steps (not merely workflow status); exact-head SHA recorded
- [ ] Documentation/examples/stdlib migration and staged compatibility review before any merge

Do not report five-backend or formatter parity until each gate independently passes. Do not treat zero-step GitHub Actions failures as source-test results.
