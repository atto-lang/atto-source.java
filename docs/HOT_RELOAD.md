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
2. parses and type-checks the candidate, including normal structural conformance;
3. verifies that the requested module explicitly declares `conforms contract`;
4. stages a fresh generation without executing guest code.

For now, the runtime loader fails closed if the source contains imports, if it
contains more than one named module, or if the requested contract is generic.
This API accepts a contract name, not a generic contract instantiation. Cross-file
contracts and dependencies require a linked-code-unit loading API and are not
silently treated as available.

A basic transactional replacement path is also available:

```text
reloadModule(codeUnitId, moduleName, source, contract)
```

The candidate is staged and initialized before the previous generation is
retired. Module startup uses the runtime's init-only entry path: it links and
initializes the named module but does **not** invoke the source program's
`main()`. The source must contain only the requested named module (plus any
contract declarations in the synthetic root module), so unrelated named modules
cannot be initialized as a side effect.

If candidate initialization fails, the previous generation remains active.
Cleanup failures are recorded as suppressed failures and cannot prevent
restoration of the previous active-generation mapping. This rollback is about
the active-generation mapping and runtime resources; it cannot undo external
side effects that module initialization may already have caused.

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
generation/context-local.

## Actor follow-up

Actors are a natural next target because their mailbox and owned state provide
an explicit upgrade boundary. Actor hot replacement should retain actor identity,
mailbox/supervision relationships, and safe state migration rules rather than
simply swapping arbitrary executable memory.

The current draft therefore provides the module-level loading and contract gate
without pretending that actor state migration is already solved.
