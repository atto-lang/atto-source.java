# Nominal enum caller-role contracts

**Status: staged compiler implementation on top of the unmerged enum frontend.**
This branch does **not** enable executable enum declarations. Java's public
TypeChecker, OwnershipChecker, TreeShaker and guest evaluator intentionally
reject enum/pattern declarations pending full runtime lowering. LLVM parity
and authenticated cross-file symbol import/link signatures are not implemented.
The incremental compiler now hashes staged policy, public enum schemas,
and private enum schemas where a public role policy depends on them. These
fingerprints **do not** authorize importers or prove call-site identity. This is
an API correctness/compiler guidance contract, **not an authentication boundary**.

## Spelling and meaning

```oreslang
define enum Callers as
    A
    B
    C
end

@CallableBy(Callers.A, Callers.B)
pub fnc foo(): void {}

@CallerRole(Callers.A)
pub fnc from_a(): void { foo(); }  // admitted once enums are executable

@CallerRole(Callers.C)
pub fnc from_c(): void { foo(); }  // compiler error
```

- `@CallerRole(E.A, E.B)` grants THIS function/routine/method the named
  caller-role cases; `@CallableBy(E.A, E.B)` specifies the allowed callers
  of THIS callee. The call succeeds iff the role sets intersect. A missing
  caller annotation means the empty role set, not `Any`.
- Roles are **nominal enum case identities**: `Module.Enum.Case` resolves
  to an actual enum declaration and nullary case. Case names, string values,
  ordinals and type aliases are never substitute credentials.
- A label reference must resolve to an enum in the **same lexical module**.
  `Enum.Case` and the redundant fully qualified `Module.Enum.Case`
  are accepted there. Cross-module and imported roles are rejected until
  import visibility and authenticated linker-resolved symbol/ABI proofs exist.
- Annotation arguments must be 1..64 distinct, qualified, nullary enum cases.
  Repeated `@CallerRole` or `@CallableBy` on one declaration is an error.
  Empty allowlists, unknown cases, case payloads, generic annotations and
  annotation use on fields/constructors/modules are rejected.
- Role grants are **lexically bound to a declaration** and **non-transitive**:
  an authorized function's helper, callback, nested lambda or downstream
  message handler does not inherit its permissions.
- The declared `pub` visibility does not override `@CallableBy`, and
  `@CallableBy` does not elevate `private` members or trusted actor hooks.
  This is a *further restriction* in addition to existing visibility, actor,
  capability and ownership checks.

## Compiler implementation

`CallerRoleContracts.validate(Ast.Program)` creates an identity-keyed
immutable role index after proving enum declarations and role references.
`requireDirectCall` works with the **resolved callee AST declaration
object**, not a string or user-controlled role parameter. The Java
TypeChecker now hooks the index into direct function/routine, module-qualified,
static method and nominal instance-method branches. The index derives the
current caller's role set **only from the identity of its checked declaration**:
user-provided role strings or sets cannot authorize calls. **Any** unindexed direct function/method target now fails closed, even
when an AST rewrite silently strips the `@CallableBy` annotation.
The index records every verified callable identity (including unguarded
declarations), and rejects raw names, null/foreign targets, detached AST
copies and duplicate callable identity reuse. Reified unindexed callables
also fail closed. This deliberately requires a future authenticated import
binder for otherwise ordinary linked external class methods; a signature
alone is no longer an authorization proof. Lambda and deferred
expression checking clears the current caller identity to avoid inheriting
authority. Module namespace values are rejected when they would expose a
protected public function through an erased record-shaped callable field;
verified direct module-qualified calls remain admitted by the role index.

**Staged restriction:** `@CallableBy` is rejected on all instance methods,
including private methods, until actual virtual dispatch and override-policy
preservation have a proven sealed method contract. `@CallerRole` on ordinary
instance methods and `@CallableBy` on static class functions are supported by
the standalone static index; the executable enum frontend remains gated.

## Incremental ABI and import boundary

- `IncrementalCompiler.abiDigest` uses a versioned **v7** fingerprint for
  exported `@CallerRole` and `@CallableBy` metadata on public functions and
  methods. Role argument ordering is canonicalized for a stable digest.
  Public nominal enum case name, wire tag, payload type and declaration
  visibility also affect the exported source ABI. A module with publicly
  exposed role metadata fingerprints only those private local enums
  referenced by the exported `@CallerRole` or `@CallableBy` annotations.
  Retagging a referenced private enum invalidates importing builds;
  unrelated private enums are excluded. When a public subclass inherits
  public methods from private ancestors reachable within the same source
  program, including ancestors declared in a different module, the ABI
  fingerprints those method signatures and caller-role contracts, plus
  private enum schemas belonging to the ancestor's defining module.
  Unrelated private classes and enums remain outside that dependency set. These fingerprints enable
  cache invalidation; they are **not** authenticated call-site metadata.
- `ImportGraph.validateLinkedImports` currently **rejects** unauthenticated
  cross-source imports exposing a public `@CallerRole` **grant** or
  `@CallableBy` **restriction**. Both are security-relevant callable metadata.
  Named function imports, module/wildcard selectors and exported class/actor
  static functions are covered, including public inherited callable surfaces.
  Whole-module/wildcard scans also inspect role-bearing public actor callables.
  A private class ancestor can expose an annotated public static method
  through a public subclass; unknown/cyclic parent identity fails closed.
  This blocks both role-grant and role-restriction erasure through imported
  signatures or record-like namespace projections until an authenticated
  declaration/role-enum import binder exists. Unannotated unrelated public
  sibling functions remain importable under their normal visibility rules.
- Future policy-bearing imported declarations require exact source identity,
  authenticated linker-bound symbol IDs, verified role enum provenance and
  override-compatible policy. Do not relax the import rejection based on a
  matching string, JSON schema or unsourced hash alone.
- New parser-only tests cover ABI invalidation, nominal enum tag/schema changes,
  namespace erasure, and protected import denial. The tests are committed but
  must not be described as executed until JDK25 CI actually runs them.

**Important remaining soundness gates** (not assertions of support):

1. Enable real enum lowering and TypeChecker admission only after typed enum
   values, sealed case identities, imported declarations and native backends
   produce matching proofs.
2. Bind all indirect calls and method references to authenticated declarations
   or reject them. The erased module-namespace path is now forbidden where
   guarded public functions are exposed; prove all other callable projections,
   overload erasure, callbacks and generic specialization cannot drop policy.
3. Prove inherited/overridden virtual method and interface dispatch may not
   weaken the caller restrictions; reject policy-bearing overridable methods
   until compatible override checks are implemented.
4. Prove identity isolation on every asynchronous, generator, actor and deferred
   callable activation. Ordinary lambda and defer-expression checking now
   clear the caller, but this is not full guest/runtime conformance.
5. Retain policy symbol identities and ABI fingerprints through tree-shaking,
   serialization, hot reload, incremental compilation, AOT and cross-file link.
6. Introduce LLVM/Wasm/.NET/Forge conformance and positive/negative executable
   tests before declaring backend parity.

The standalone policy unit tests assert enum-role validation and direct-call
decisions. Java's public compiler-ingress test specifically asserts **rejection**
of source enums until the above language feature is integrated.
