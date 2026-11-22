# Compile-time borrow safety: escape boundary audit (2026-10-26)

**Status:** static compiler hardening, not a whole-program or all-backend soundness
proof. This patch changes the Java/Graal `OwnershipChecker`; LLVM, WASM,
.NET and JavaScript frontend parity still require independent source fixtures.

## Language invariant

Borrow compatibility, exclusivity, use-after-move and static lifetime
eligibility must be established **before lowering**. No guest runtime borrow
counter, dynamic borrow permission check, or actor scheduler serialization may
be used to justify an otherwise-unsafe source program. GC, bounds checks,
memory reclamation and actor runtime synchronization remain distinct.

## Escape boundaries tightened on this branch

- Class and actor field types reject *transitive* loans nested in Option,
  List, tuple, struct/record shapes, unions and generic arguments; imported
  public class layouts are validated before they enter the ownership index.
- `yield expr` rejects a borrow created by evaluation of the yield
  expression itself. `yield* expr` also rejects nested/delegated borrowed
  elements, without requiring an existing lexical loan before evaluation.
- Returning a wrapper that contains a loan is rejected: only direct returned
  loans rooted in incoming borrowed parameters use the existing provenance
  contract.
- Suspension (`await`, `rt cooperate`, blocking channels/select and
  suspending call checks) treats transitive borrowed parameters as live loans,
  even if the top-level type looks owned.
- Ordinary owned local bindings, by-value callable arguments, constructor
  arguments/instantiations and sum constructors reject nested borrowed data
  unless a lexical lifetime is explicitly modeled.
- Owned list/tuple/struct initialization, assignment, trap wrapping and channel
  writes also reject transitive loan-bearing values before lowering.
- Closures reject nested borrowed captures; expression-bodied lambdas apply
  the explicit-return provenance check to direct borrowed results and reject
  borrowed wrappers. The `rt take`, `rt copy`, and `rt share` primitives
  cannot conceal loans inside owned-looking generic carriers.
- Independently scheduled task captures and deferred callbacks reject nested
  borrowed carriers before execution. Generic destructuring fails closed on
  borrowed aggregate sources instead of manufacturing untracked lexical
  aliases; direct immediate tuple destructuring retains its scoped binder
  checks. Value-match arms cannot export nested borrowed branch-local values.

All decisions are made over the AST's `TypeRef` tree by `OwnershipChecker`.
No borrow state or counter is introduced in the guest runtime.

## Boundaries intentionally not certified by this change

- Alias expansion and method/field generic substitution still require
  whole-program proof; constructed generic types with explicit borrow
  arguments now fail closed.
- Flow-sensitive shared/exclusive loan proof across all advanced control-flow
  joins, callbacks, closures and imports.
- Actual LLVM / WASM / .NET / JavaScript frontends and emitted-code inspection.
- Whole Maven suite, native-image AOT and exact-head carrier CI execution.

A backend must implement equivalent static rejection or explicitly fail closed.
Do not market cross-backend zero-overhead borrowing as verified until negative
fixtures and codegen inspection run for every supported target.

## Verification commands (when exact-source JDK 25 runners are available)

```sh
mvn -Dtest=StaticBorrowEscapeBoundaryTest,BorrowControlFlowHardeningTest,GeneratorLanguageTest test
mvn verify
```

Private-source cross-repository CI remains subject to
`docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`: an unapproved funded runner
is **not** authorization to export private Git objects.

- The recursive borrowed-type proof also expands local named type aliases,
  including chained aliases and substituted generic arguments, before an
  owned field/call/binding is admitted. Unresolved or cyclic aliases fail
  closed. Imported opaque aliases still require linked type evidence.

- Fixed-arity argument spreading is an owned-value boundary: `...arguments`
  and typed tuple spreads reject direct or transitive borrowed slots before
  expansion as ordinary by-value parameters. Borrowed parameters must be
  forwarded explicitly so lifetime and mutability can be checked. The
  owned fixed-tuple positive control prevents blanket rejection.
