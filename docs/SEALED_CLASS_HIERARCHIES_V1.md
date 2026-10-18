# Sealed, non-sealed, and final class hierarchies — contract v1

> **Status (2026-10-15): DESIGN / PROSPECTIVE. NOT IMPLEMENTED OR VERIFIED.**
> This document is a target source-language contract, **not** proof that the Java/GraalVM or LLVM parser,
> checker, evaluator, AOT, imports, formatter, or matching machinery supports these forms.
> The executable negative and positive acceptance cases are tracked in
> `contracts/v1/sealed-class-hierarchy.json`. Existing Java/LLVM code must fail closed rather
> than claiming partial parsing as completed semantics.

## Source spelling

```ores
define abstract sealed class Shape permits Circle, Polygon as
  abstract area(): float;
end

define final class Circle extends Shape as
  pub area(): float { return 1.0; }
end

define non-sealed class Polygon extends Shape as
  pub area(): float { return 2.0; }
end

define class Triangle extends Polygon as
end
```

- `define sealed class C permits A, B as ... end`: closed **direct-child** whitelist.
  `permits` is mandatory and explicit; no filename-order or module-local implicit inference.
- `define non-sealed class D extends C as ... end`: explicitly open branch, valid only
  if a direct class parent is `sealed` and expressly permits `D`. Subsequent descendants
  of `D` are not restricted by `C`'s permit list.
- `define final class D as ... end`: cannot be extended, whether or not `D` has a
  sealed parent. It can still implement interfaces.
- `abstract` is independent of `sealed` and `non-sealed`: `abstract sealed`
  and `abstract non-sealed` are legal. `abstract final`, `sealed final`,
  and `sealed non-sealed` are errors.
- An ordinary `define class` remains open **unless** its direct superclass is sealed;
  then it must explicitly choose exactly one of `final`, `sealed`, or `non-sealed`.
- A sealed class must name at least one direct permitted subclass. A sealed subclass must
  declare its own `permits` list; sealing is not transitive by default.
- `final` in a **class header** is unrelated to the existing single-assignment
  `final` **field** modifier or `final const` compile-time requirement.
- Canonical spelling is `non-sealed` with the hyphen. Do not invent `nonsealed`,
  `unsealed`, or `open` aliases without a separate language decision.

## Nominal identity and visibility

- The permitted name resolves to a **specific nominal class declaration** in the
  sealed parent's defining package/module-generation, never by unqualified string
  equality, structural shape, a local type alias, host reflection, or a guest-controlled
  runtime descriptor.
- Every permit entry must resolve unambiguously to an accessible **direct** subclass.
  Reject duplicate entries, unknown entries, self-permits, indirect-only descendants,
  incompatible generic specializations, a permitted class that does not extend the parent,
  and inheritance cycles. A permitted abstract class is allowed if it commits to
  `sealed` or `non-sealed` itself.
- No outside module or hot-loaded plugin may add a direct subclass to a sealed declaration
  without a new trusted, versioned version of that sealed declaration. Persist the
  authoritative permit identities in compiled public API/ABI fingerprints and module
  import metadata; reject stale/downgraded or inconsistent dependencies before execution.
- Current Java documentation describes more than one class parent, while the more recent
  nominal `Error` hierarchy is a single-parent tree. This proposal does **not** silently
  change existing multi-parent semantics. Until the one-class-parent policy is
  reconciled across the compiler, **every** direct sealed parent must authorize the child.
  A later single-parent restriction can be a separate migration; interfaces/traits remain
  the preferred additional abstraction mechanism.
- Do not conflate compiler-level sealed *class hierarchies* with actor `state {}` sealing
  or unforgeable runtime capabilities. Actor class families, sealed interfaces/traits,
  and sealed host/runtime intrinsics need independent admission and security review.

## Exhaustive matching and algebraic closure

- A sealed type gives a finite list of **direct** branches, not necessarily a finite
  set of all concrete runtime types. Recursively closed branches (`final` or `sealed`
  with all descendants closed) can prove total coverage.
- Any `non-sealed` descendant opens its subtree: checking merely today's known concrete
  descendants must **never** mark an exhaustive match. Require a covering parent branch
  or wildcard/default, using the existing Oreslang match rules.
- Guarded/refutable arms do not count as unconditional coverage unless the compiler
  proves the guard tautological. Preserve Oreslang's explicit `return match` and
  `do match` syntax and its ordered `match ... over` behavior.
- Unsupported nominal match or runtime dispatch behavior remains a compilation error,
  not a fallback to a host `instanceof` check or a claim of exhaustiveness.

## Enforcement points

1. Lexer/parser: recognize the canonical modifiers and `permits` in all legal class
   header orders, reject malformed or contradictory modifiers without treating them
   as ordinary names or field modifiers. Preserve source positions in diagnostics.
2. AST and exports: retain openness and normalized, identity-resolved direct permits;
   update all compatibility constructors, compilation caches, imported class metadata,
   incremental fingerprints, serialization, and hot-reload generation manifests.
3. Type checker: validate the whole hierarchy after collecting declarations (including
   imports); reject all unauthorized or unclassified direct children; enforce `final`
   and resolve nominal symbols securely across modules and aliases.
4. Runtime/AOT: prevent malformed foreign ASTs or stale metadata bypassing the checker;
   verify the same ancestry contract across direct calls, dynamic dispatch, generated
   code and hot reload. Never trust a guest-created `ClassDecl` or descriptor by name.
5. Both backends and formatter: agree on syntax and diagnostics; maintain LLVM fail-closed
   behavior until native nominal class hierarchy execution exists.
6. Testing: parse/typecheck positives and negatives; exact-source Java compiler/JVM,
   LLVM native source-to-IR/CTest, formatter round trip, cross-module imports,
   optimizer/AOT and generation-staleness tests. No CI result is claimed here.

## Release acceptance

Only declare support complete after all three are true: (a) both language frontends
enforce authorization and finality for every instantiable/imported hierarchy,
(b) exhaustiveness never treats an open branch as closed, and (c) the executable
exact-commit cross-backend and hot-loading tests pass. Until then keep implementation
PRs draft and diagnostics fail closed.
