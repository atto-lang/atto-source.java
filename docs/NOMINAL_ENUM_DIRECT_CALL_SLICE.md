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


## Additional hardening audit

- An unbound module facade invocation cannot search overloaded candidates
  and pick the only public method. The module dispatch path checks the
  entire qualified name + arity family first, then fails closed.
- Qualified namespace calls fail compile-time visibility checks before any
  target binding; unqualified enum overloads also respect their declaring
  module's private authority.
- At execution, an allegedly compiler-bound direct target is verified
  against the evaluator's indexed declaration identity, accessible name,
  declaring code-unit/module and fixed non-spread arity. Module-qualified
  targets must also have the exact expanded argument count.
- The public `TreeShaker.shake` API is guarded, not just
  `OresCompiler.compileForBuild`, so callers cannot bypass the AST-identity
  invalidation gate.
- Compiler proof work is bounded to **128 same-arity candidates** per
  family and **64 parameters** per nominal signature. These are hard
  compiler admission limits, not runtime candidate-search limits.
  LLVM's C++ policy has an aligned draft follow-up PR #353.
- Regression fixtures cover private module selection, an alias/unbound
  module path, the direct optimizer API, and proof-complexity bounds.

Verification caveat: GitHub Actions is currently failing before runner
allocation, with zero executed steps and no usable compiler/test logs.
These patches are committed but have not been established green.
