# Oreslang hot module loading — draft

This document defines the first, deliberately small hot-loading contract for OresVM.

## Module contract syntax

Modules use dedicated contracts. Traits are not applied to modules.

Canonical syntax:

```ores
pub define contract WorkerApi as
  fnc run(): int;
end

define module Worker conforms WorkerApi as
  pub fnc run(): int {
    return 1;
  }
end
```

A module may list multiple contracts:

```ores
define module Worker conforms WorkerApi, HealthApi as
  ...
end
```

The compiler checks the complete exported module shape, including public fields,
field binding requirements, callable signatures, and contract identity. An
unqualified contract field requires a readable value of the right type; an
explicit `const`, `val`, or `let` requires that exact binding kind. The declared
`conforms` list, contract-versus-interface identity, and field binding
requirements are part of the incremental ABI, so changes invalidate dependent
units.

`@AdheresTo` is not a module-conformance mechanism and must remain rejected.

## Basic hot loading

The runtime now exposes a supervisor-side, contract-gated loading path:

```text
loadModule(codeUnitId, moduleName, source, contract)
```

The loader:

1. validates code-unit/source admission limits before parsing;
2. inspects the annotation-expanded declarations and verifies the requested module explicitly declares `conforms contract`;
3. fails closed for imports, unrelated named modules, executable root-scope declarations, and any generic module-contract instantiation;
4. performs the normal full type/capability admission;
5. stages a fresh generation without executing guest code or changing the active-generation mapping.

For manual activation, use `initializeModule(candidate)` and then
`activateModule(candidate)`. Module candidates cannot be started through the
legacy `Generation.start()` method; the manager-owned initializer ensures failed
candidates are retired and do not leak generation slots or code-image leases.

After initialization, the host may invoke an exported function through
`generation.invokePublic("Worker.run", args...)`. Invocation is bound to that
exact generation, not looked up again through the current active slot. That lets
in-flight work finish against an old implementation while new work selects the
new active generation. Invocation before initialization, or after retirement/
close, is rejected.

This API accepts contract names, not generic contract instantiations. The
incremental compiler supports direct exported named cross-file contract conformance
with linker-supplied type metadata, but the runtime hot-loader does not yet accept
a source graph. Cross-file contracts and dependencies are rejected by this
runtime API rather than silently treated as available.

A basic transactional replacement path is also available:

```text
reloadModule(codeUnitId, moduleName, source, contract)
```

The candidate is staged and initialized before it becomes the active generation.
Module startup uses the runtime's init-only entry path: it links and initializes
the named module but does **not** invoke the source program's `main()`. The source
must contain only the requested named module (plus any contract declarations in
the synthetic root module), so unrelated named modules cannot be initialized as
a side effect.

Before staging a replacement, the runtime fingerprints the full set of
declared module contracts and their inherited surfaces, including declaring-module
identity, callable signatures, and field binding requirements. An ordinary reload
must preserve this contract-set ABI; redefining a same-named contract to a weaker
or different shape, moving its declaration, or dropping an additional declared
contract is rejected before activation. An explicit contract/state migration
protocol is not implemented yet.

The generation-scoped host API `generation.invokePublic(name, args...)` accepts
only callable names/arities included by the module's contract set. The call stays
bound to that exact generation, so an old generation can continue serving pinned
callers during drain while new callers use the replacement.

Contract-gated module generations do **not** expose their raw Graal `Context`
through `Generation.context()`. Exposing that context would let host code parse
and evaluate a separate source in the same runtime context, bypassing the
module-specific admission/contract surface. Use the generation-scoped API instead.
The legacy generic loader retains its historical raw-context accessor for
compatibility; it is not covered by the narrower module contract guarantee.

If candidate initialization fails, the previous generation remains active.
Cleanup failures are recorded as suppressed failures and cannot prevent
restoration of the previous active-generation mapping, including the manager's
single-unit convenience pointer when another code unit was most recently active.
After a successful switch, the previous generation remains open for actors or
requests pinned to it; the supervisor must call `retire(oldGeneration.id())`
after those users have drained. This avoids tearing down an old generation
while it may still own live actors. Rollback restores runtime selection/resources;
it cannot undo external side effects that module initialization may already have
caused.

## Deliberate v0 limitations

This draft does **not** claim:

- automatic migration of arbitrary module state;
- migration of actor-owned state;
- compatibility proofs between old and new state schemas;
- arbitrary live closure migration;
- distributed/process-crossing hot code replacement;
- sharing mutable module/runtime state between generations.

Those require explicit state/version migration semantics. The generic hot-loader
API remains unchanged for existing code-unit callers; the module-specific API is
the narrower contract-gated path described above.

The existing generation model remains the isolation boundary: code images may be
shared when policy permits, while mutable evaluator/module/closure state remains
generation/context-local. Calling `Generation.close()` directly also retires that
generation from the manager, so it cannot leave a closed generation in an active
slot. Repeated initialization/activation of the current module generation is
idempotent; an older staged candidate cannot overwrite a newer active generation.

## Actor follow-up

Actors are a natural next target because their mailbox and owned state provide
an explicit upgrade boundary. Actor hot replacement should retain actor identity,
mailbox/supervision relationships, and safe state migration rules rather than
simply swapping arbitrary executable memory.

The current draft therefore provides the module-level loading and contract gate
without pretending that actor state migration is already solved. Code-unit IDs
are normalized before they are used as generation keys so path aliases such as
`./worker.ores` and `worker.ores` cannot create separate active slots. Manager
shutdown is terminal: it detaches active/registry maps under lock, closes detached
generations outside the manager lock, and rejects later load/activation requests.
Module initialization also runs outside the manager lock; concurrent replacement
candidates are checked again at publication so a stale candidate cannot overwrite
a winning generation.

## Cross-tenant sharing authorization and adversarial admission

Immutable code-image reuse **does not imply cross-tenant approval**. A
`HotReloadManager` owns an opaque authority identity independent of the guest
code-unit path, source digest, or `IsolatePolicy` value. An adversarial manager
may use the process-wide immutable code image only after **that same manager**
explicitly approves that exact code-unit ID and source digest. A grant or revoke
on manager A must not affect manager B even if they use byte-identical source,
the same code-unit path, and equal supervisor policies.

Approvals are bounded to 64 distinct code units/versions per manager, require
ordinary untrusted capability admission, and are revoked when the manager
closes. Existing image leases remain pinned until their generations retire;
revoking an approval prevents *future acquisitions* and does not invalidate
an already running generation. Approval does not confer extra guest capabilities.

For adversarial generic `loadAndStart`, limits are enforced on the **raw**
code-unit path before normalization, so path segments that normalize away cannot
bypass the maximum input-size check.

The `active()`, `active(codeUnitId)`, and `activeGenerations()` APIs do
not return generations already marked retiring or closed. Callers must still
coordinate shutdown/draining of in-flight operations; an active lookup is not a
long-lived lease that pins a generation.

Security acceptance requires targeted tenant-isolation, quotas, close/revoke
and rollback tests, plus an actual native Graal isolate run. Maven tests on
a normal JVM do **not** establish hostile-tenant containment.
