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
field binding requirements, callable signatures, and contract identity. The
declared `conforms` list is also part of the incremental ABI, so changing the
declared module contract invalidates dependent units.

`@AdheresTo` is not a module-conformance mechanism and must remain rejected.

## Basic hot loading

The runtime now exposes a supervisor-side, contract-gated loading path:

```text
loadModule(codeUnitId, moduleName, source, contract)
```

The loader:

1. parses and type-checks the candidate;
2. verifies that the requested module explicitly declares `conforms contract`;
3. performs the normal full module-contract structural validation;
4. stages a fresh generation without replacing unrelated code units.

A basic transactional replacement path is also available:

```text
reloadModule(codeUnitId, moduleName, source, contract)
```

The candidate is staged and started before the previous generation is retired.
If candidate startup fails, the previous generation remains active.

## Deliberate v0 limitations

This draft does **not** claim:

- automatic migration of arbitrary module state;
- migration of actor-owned state;
- compatibility proofs between old and new state schemas;
- arbitrary live closure migration;
- distributed/process-crossing hot code replacement;
- sharing mutable module/runtime state between generations.

Those require explicit state/version migration semantics.

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
