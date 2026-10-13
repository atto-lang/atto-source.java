# Nominal enum overloads — direct-call executable slice

Status: **draft implementation, exact-head tests not yet executed**.
Stacked on Java PR #856 (static policy) and Java PR #623 (tagged enums).

## Scope currently implemented in this branch

- Same-name, same-arity **top-level/module** `fnc` or `routine`
  declarations may coexist only when their signatures are pairwise
  distinguishable by the canonical IDs of eligible primitive-payload
  nominal enum declarations.
- Every differing parameter position must have **distinct eligible enum**
  type identities. All nondiscriminating positions must have identical
  admitted primitive types; `String`/`string` are canonicalized.
- For a direct source call, the type checker derives the nominal owner of
  each *static argument type* (including a constructed `Color.Red()` case).
  No variant tag, payload field, record layout or runtime value is examined.
- The selected function declaration is stored by **call AST object identity**
  in the immutable checked `Ast.Program.boundDirectCalls` snapshot.
  Equal-looking call expressions in different functions do not alias.
- The evaluator uses the same bound declaration during synchronous,
  suspendable, and local module-qualified invocation, rather than calling
  a name/arity lookup to choose the target.
- OwnershipChecker uses that same declaration to validate arguments,
  borrowing and move rules. Caller identity may not be inferred from
  function names alone.
- The runtime still indexes and checks the whole nominal family but
  rejects any ambiguous **unbound** lookup. Invocation from host APIs,
  imports, function-value extraction, module dynamic lookup, or a
  runtime-reconstructed name/arity cannot silently select an overload.
- Tree-shaken/AOT build optimization fails closed for same-arity enum
  overloads because TreeShaker currently clones AST calls and destroys
  the checked call-key identity. A later patch must rebind the transformed
  output or produce stable emitted callable IDs.

## Still explicitly prohibited

- Same-arity overload selection by primitive types, generic type
  parameters, classes/interfaces/traits, structural records or structs,
  `Unknown`, `Any`, union types, implicit conversions or return types.
- Overloading on cases or enum payload values: `Color.Red()` and
  `Color.Blue()` both have nominal owner `Color`.
- Same-arity methods/static class functions: existing arity-only
  vtables cannot distinguish them. Native LLVM source enum overloads are
  also not admitted.
- Dynamically invoking a same-arity enum overload from imported code,
  a host API or a function value without an explicit bound signature.
- Transforming/reusing AST nodes after typing without rebinding their
  callable identities.

## Runtime and test safety gates

Tests include real Java polyglot execution of both direct and
module-qualified calls, pairwise disjointness, multi-position proof
completeness, nested/repeated AST call identity, `Any`/union ambiguity,
invalid primitive-only differentiation, source value extraction,
same-arity class method rejection, and build-tree-shaking fail-closed
behavior.

No passing Java/GraalVM or LLVM test run is claimed until an actual
runner executes the exact PR head. All PRs remain drafts until full
verification and ABI parity.
