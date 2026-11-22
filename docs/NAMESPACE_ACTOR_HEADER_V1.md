# Namespace actors with out-of-line methods — admission v1

## Proposed source shape

```atto
namespace foo;

define actor Foo implements ActorXYZ;

pub mtd (Foo f) init(): f {
    return f;
}

pub mtd (Foo f) bar(): void {
    return;
}
```

This **parses as source metadata**, but does **not** compile into an executable
actor or prove `ActorXYZ` conformance. The exact example also declares
`Foo` privately while exposing public methods. Public API binding on a
private actor intentionally fails; use `pub define actor Foo ...;` only if
the actor type is meant to be exposed.

### Implemented in this patch

- Preserve a semicolon-terminated nominal actor header and explicit
  `implements` reference list in `Ast.StandaloneActorDecl`; no hidden
  actor instance or synthetic receiver alias is created.
- Parse `pub mtd (Foo f) bar(): void` as an external actor method without
  requiring `define`; the older `define [pub] mtd` spellings remain.
- Preserve `init(): f` as a parsed return-type reference, **not** as a
  proven self-return or a capability to return mutable actor state.
- For a bare semicolon actor with no contract obligations, the static binder
  may index external method slots after checking nominal target, visibility,
  duplicates, namespace and source unit. All external bodies still fail
  executable type checking.
- Reject an actor header with an unverified `implements` clause at the
  nominal binder boundary, rather than incorrectly treating a named
  contract as a discharged proof.
- Reject unchecked inherited actor slot attachment; reject borrowed or
  closed structural `ActorMail` payloads at the external receive boundary.

### Contract and runtime requirements — NOT IMPLEMENTED

1. `ActorXYZ` must resolve to a declared **actor-targeted** contract in
   the authenticated, manifest-locked package/namespace source graph, never
   to an ambient module, ordinary interface, same-name foreign package,
   or an unattested caller-provided map.
2. Conformance must check every public concrete method against the exact
   nominal receiver, name, arity, parameters, return type, mutability,
   visibility, effects, async/suspension and lifecycle rules. Reject
   duplicate or ambiguous witnesses across files and inherited slots.
3. Typed call resolution must distinguish `f.bar()` on the current
   actor context from an external `ActorRef<Foo>` invocation. The former
   stays in the actor's current nonreentrant turn; the latter must be
   mailbox-dispatched with quotas, cancellation and generation fencing.
4. `init(): f` needs a deliberate typing decision: the receiver variable
   `f` is not a nominal type and must not implicitly reveal the actor's
   owned state or an unrestricted actor pointer. If a handle is returned,
   it must be a compiler-issued generation-fenced `ActorRef<Foo>`, not an
   arbitrary cast of `f`. A method called `init` must not bypass trusted
   activation/on_start lifecycle gates.
5. No actor-owned state loan, supervisor authority, `ActorContext` or
   runtime actor object is minted by parsing or static binding.

### Verification gate

Run `NamespaceActorHeaderAdmissionTest`,
`CrossFileActorReceiverBindingTest`, `ActorReceiverBinderTest` and
`ExternalActorReceiverAstAdmissionTest` on the exact PR head with
non-zero executed GitHub Actions steps and non-skipped Surefire receipts.
An admitted parser suite is still not executable guest proof.

Before merge, reconcile this branch with the parallel
inheritance/mailbox hardening PR #2828, actor-contract PR #2792 and
manifest-backed namespace build PR #2813. Obtain independent review and
real guest tests; only then proceed to Java/Graal, LLVM, .NET NativeAOT,
Wasm and Forge parity. No cross-org private source mirror is authorized
by this patch.
