# Nominal enum caller-role contracts

**Status: staged compiler implementation on top of the unmerged enum frontend.**
This branch does **not** enable executable enum declarations. Java's public
TypeChecker, OwnershipChecker, TreeShaker and guest evaluator intentionally
reject enum/pattern declarations pending full runtime lowering. LLVM parity
and cross-file symbol import/link signatures are not implemented. This is an
API correctness/compiler guidance contract, **not an authentication boundary**.

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
user-provided role strings or sets cannot authorize calls. Unindexed guarded
AST targets and reified function values fail closed. Lambda body checking
clears its caller identity to avoid inheriting authority.

**Staged restriction:** `@CallableBy` is rejected on all instance methods,
including private methods, until actual virtual dispatch and override-policy
preservation have a proven sealed method contract. `@CallerRole` on ordinary
instance methods and `@CallableBy` on static class functions are supported by
the standalone static index; the executable enum frontend remains gated.

**Important remaining soundness gates** (not assertions of support):

1. Enable real enum lowering and TypeChecker admission only after typed enum
   values, sealed case identities, imported declarations and native backends
   produce matching proofs.
2. Bind all indirect calls and method references to authenticated declarations
   or reject them. Ensure module callable projections, overload erasure,
   callbacks and generic specialization cannot drop the policy.
3. Prove inherited/overridden virtual method and interface dispatch may not
   weaken the caller restrictions; reject policy-bearing overridable methods
   until compatible override checks are implemented.
4. Isolate roles in every asynchronous, generator, actor and deferred callable
   activation, not merely ordinary lambdas.
5. Retain policy symbol identities and ABI fingerprints through tree-shaking,
   serialization, hot reload, incremental compilation, AOT and cross-file link.
6. Introduce LLVM/Wasm/.NET/Forge conformance and positive/negative executable
   tests before declaring backend parity.

The standalone policy unit tests assert enum-role validation and direct-call
decisions. Java's public compiler-ingress test specifically asserts **rejection**
of source enums until the above language feature is integrated.
