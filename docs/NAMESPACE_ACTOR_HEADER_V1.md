# Namespace actors with out-of-line methods — admission v1

## Proposed source shape

```atto
namespace foo;

declare pub actor Foo implements ActorXYZ;

define state on actor Foo as {
    let int x: 5;
}

external mtd (Foo f) init(): void {
    return;
}

external mtd (Foo f) receive(ActorMail<int> mail): void {
    return;
}

pub mtd (Foo f) bar(): void {
    return;
}
```

This **parses as source metadata**, but does **not** compile into an executable
actor or prove `ActorXYZ` conformance. The example declares a public nominal actor and its private sealed state.
The `init` and `receive` entry points are **runtime-controlled**, not public
callable methods. Actor contract linking and critical state execution remain
separate unimplemented gates.

### Implemented in this patch

- Preserve a semicolon-terminated `declare actor` nominal header and explicit
  `implements` reference list in `Ast.StandaloneActorDecl`; no hidden
  actor instance or synthetic receiver alias is created.
- `declare actor Foo;` is the canonical private body-free header;
  `declare pub actor Foo;` exports that nominal actor. Visibility **must
  follow** `declare`: `pub declare actor Foo;` is rejected.
  `define actor Foo;` is explicitly rejected, while
  `define actor Foo as ... end` remains the separate inline form.
- Parse `pub mtd (Foo f) bar(): void` as an external actor method without
  requiring `define`; the older `define [pub] mtd` spellings remain.
- Require explicit private `external mtd` on `init` and `receive`
  for declared actors. `init` is zero-argument/`void`; `receive` must
  accept exactly one `ActorMail<T>` and return `void`. A plain `pub mtd
  init(): Foo` must not grant a raw actor return or runtime ingress.
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
4. `external mtd init(): void` must run **only** on trusted actor
   activation with generation fencing. `external mtd receive(ActorMail<T>)`
   must run **only** on mailbox admission; neither may be invoked, captured,
   exported or returned as an ordinary method. Parsed methods confer no
   runtime ingress authority.
5. No actor-owned state loan, supervisor authority, `ActorContext` or
   runtime actor object is minted by parsing or static binding.

### Verification gate

Run `NamespaceActorIngressAdmissionTest`, `NamespaceActorStateAdmissionTest`,
`NamespaceActorHeaderAdmissionTest`,
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
