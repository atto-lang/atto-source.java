# Persistent scalar actor i64: checked execution slice

**Status (2026-11-10):** experimental opt-in source-checked host executable v1, stacked on [persistent syntax + SQLite PR #2769](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2769). It is **not** generalized `ActorRuntime` guest-language lowering and remains incompatible with arbitrary `state {}` schemas. The ordinary `TypeChecker` continues rejecting unlowered `Ast.PersistentActorDecl` instead of silently treating it as a regular actor.

## Supported AttoLang source shape

```atto
define persistent actor Counter as
  state {
    let int count: 0;
  }
  increment(int amount) -> int ::
    return self.handle_state(critical-write |s| -> {
      s.count = s.count + amount;
      s.count;
    });
  end
  get_count() -> int ::
    return self.handle_state(critical-read |s| -> s.count);
  end
end
```

Production `Parser` emits a dedicated `PersistentActorDecl`; `PersistentScalarMirV1.lower(source)` checks the actual AST, the existing `ProtectedScalarMirV1` write-lease proof, and an exact read-lease token/provenance pattern. A public compiler API `OresCompiler.compilePersistentScalarI64(source)` exposes the same opt-in plan. Only a private actor with one owned `int` slot, a checked `increment(int)`, and a `get_count()` may lower. No imports, other declarations, unverified escapes, heap references, alternative methods, arbitrary side effects, nested awaits, loops, pointers or user-defined `handle_state` calls are accepted. A source token-kind mismatch is rejected.

`PersistentScalarActorV1.open(plan, store, address)` is trusted **host** activation. It requires the supervisor to select an authorized `DurableActorStore`, use the plan's `initial_snapshot()` for first provisioning, bind logical identity and acquire the storage activation epoch. It verifies state version, 8-byte network-order scalar codec, schema version, and code-generation digest before using a new actor-private `ScalarStateContainer`. Pending delivered inputs are replayed **before** exposing the actor instance. No guest actor can supply an arbitrary path, SQL statement, or raw durable-store authority.

`increment(delivery_id, amount)` first enqueues a stable durable delivery, computes checked arithmetic, and performs one `commitTurn` that consumes the earliest pending inbox record and advances the snapshot in SQLite. Only after a verified storage commit does the actor-private region advance and the method return committed success. Duplicate delivery IDs return an empty result; no duplicate transition occurs. Every ambiguous transaction error poisons the activation, which cannot serve another turn until a new fenced reactivation. Arithmetic overflow poisons the activation and preserves the still-pending message for explicit supervisor recovery/quarantine.

A normal close hibernates the verified activation and closes the private scalar region; an unclean process crash requires no cleanup to recover committed state. The actor's current `get_count()` is a private snapshot read, **not** a remote-fenced read for supervisors whose active epoch may have been superseded. Supervisors MUST revoke/reconcile old actor references before new incarnation publication. Strong read-fencing needs further SPI support.

## Verified target and gaps

`PersistentScalarActorV1Test` covers real SQLite state recovery, deduplication, pending message replay, cross-connection stale epoch rejection, malformed codec and schema mismatch, overflow failure, FIFO violation rollback and deliberate **separate OS process** halt/restart. `SqliteDurableActorStoreTest` runs alongside it to prevent storage regressions. The private CI carrier uses exact verified source SHA blobs; it does not receive source-org credentials.

The next general-language milestone still requires:
- typed MIR lowering for arbitrary serializable state fields and user-defined receive methods;
- real `ActorRuntime` spawn, supervisor lifecycle and mailbox integration;
- a compiler-proven state loan across all permitted returns/closures/async boundaries;
- code-generation migration/quarantine policy, event-log append and timer support;
- full Java/GraalVM guest, Native Image and other backend conformance.

Do not treat a passing subset host test as proof of the general persistent actor language feature.
