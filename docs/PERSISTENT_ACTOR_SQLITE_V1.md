# Persistent actors and SQLite v1 — compiler/storage integration milestone

**Status (2026-11-10):** `define persistent actor` parses into its own protected AST node, including the required `state {}` container. The Java type checker **rejects execution** with `PERSISTENT_ACTOR_MIR_UNSUPPORTED` until compiler-enforced state loans and actor-turn lowering are complete. Independently, `SqliteDurableActorStore` implements the existing host-side `DurableActorStore` contract, but is not automatically installed by `ActorRuntime`. Do not claim end-to-end guest execution or cross-backend parity.

## Source surface (target semantics)

```atto
define persistent actor Counter as
  state {
    let int count: 0;
  }

  increment(int delta) -> int ::
    // Target syntax: compiler-owned critical-write loan (not yet lowered).
    self.handle_state(critical-write |s| -> {
      s.count = s.count + delta;
      return s.count;
    });
  end
end
```

`state {}` is the one **mutable, actor-owned** state container, not a Clojure-style immutable persistent map. A persistent actor is an ordinary actor in scheduling, messages, supervision and isolation, with opt-in durable state and inbox/outbox coordination. No direct external state access, no guest-controlled file handles and no implicit sharing between actor heaps. The parser currently permits a restricted protected method form `name(Type arg) -> Type :: ... end`, retaining original body token kinds for later typed MIR verification. It rejects missing state containers, duplicate field names, and unbounded protected declarations.

## Storage SPI

`DurableActorStore` is the host-owned, driver-neutral port. Its `Address(tenantId,workflowId,runId,nodeId)` is a **logical** actor identity, not a capability. The runtime must authenticate this address, bind it to a supervisor-issued activation epoch and never let arbitrary guest strings select another tenant's store rows.

- `bootstrap(address, snapshot)` is an SQLite-adapter-specific trusted provisioning operation; it cannot reset an existing actor. Version 0 represents the initial `state {}` value.
- `activate(address)` atomically advances the epoch and returns encoded state, unconsumed inbox and unacknowledged outbox; a new epoch fences off all previous activations, including across connections.
- `enqueue(address, message)` durably admits a delivery ID once, even if the actor is dormant.
- `commitTurn(turn)` transactionally verifies epoch + expected version + pending delivery ID, consumes that inbox row, advances the snapshot by exactly one version and writes outbox intents with unique actor-scoped intent and idempotency keys. A failure rolls back all writes.
- `hibernate(address, epoch)` requires scheduler quiescence and fences the exact activation.
- `acknowledge(address, epoch, intentId, receiptId)` records an idempotent transport receipt after independent external reconciliation.

Example Java-host setup (requires an available SQLite JDBC driver):

```java
var store = SqliteDurableActorStore.open(Path.of("actors.db"));
var key = new DurableActorStore.Address("tenant", "workflow", "run", "counter");
store.bootstrap(key, new DurableActorStore.Snapshot(
    0, 1, "counter-code-v1", 0, initialEncodedState));
var activation = store.activate(key);
// The supervisor must verify schema/code identity, decode into an actor-private
// heap, and admit turns under its execution lease before calling commitTurn.
```

The SQLite adapter uses `BEGIN IMMEDIATE` to serialize writers, WAL with `synchronous=FULL` for host-opened file connections, SQL parameters, foreign-key checks, bounded 16 MiB state/mail payloads, and a SHA-256 state-bytes checksum. A checksum is not a signature or encryption. Externally supplied JDBC connections must be configured for the desired durability settings by their owner. The adapter does not expose SQL to guest actors or choose a file path for them.

**Crash boundary:** durable success may be acknowledged only *after* `commitTurn` commits. If the runtime mutated live state speculatively and the commit fails, it must discard that speculative version and restore from the last committed snapshot before admitting further messages. Never publish a successful acknowledgement from the speculative heap. An unknown external delivery result is reconciled using a stable idempotency key; SQLite atomic commit does not provide exactly-once network side effects.

**Schema evolution:** `commitTurn` rejects changes to schema version or code generation. Migration requires an explicit, validated offline or fenced upgrade protocol; the adapter never silently coerces old rows. `activate` returns snapshot metadata for the supervisor to check **before guest code executes**.

## Tests and gaps

`PersistentActorDeclarationTest` pins exact syntax/AST preservation, empty state, duplicate state rejection, contextual identifier compatibility and fail-closed compilation. `SqliteDurableActorStoreTest` exercises reopen/recovery, duplicate ingestion, stale activation fencing across two connections, rollback of conflicting outbox intents, schema mismatch, checksum corruption, tenant separation and payload bounds.

Test command (source repo with Maven and dependencies):

```sh
mvn -Dtest=PersistentActorDeclarationTest,SqliteDurableActorStoreTest test
```

Still needed for a usable `persistent actor` language feature: typed `state {}` field/initializer validation; verified `critical-read`/`critical-write` loans and continuation restrictions; a snapshot codec and schema registry; supervisor-owned store selection and activation; actor-turn atomic transition and commit-before-reply; outbox/timer dispatcher; recovery/migration/quarantine; per-backend lowering and compiled guest execution tests. Event logs and durable timers intentionally remain **outside** `DurableActorStore` v0; extend the port transaction atomically for event sourcing, rather than recording events in a second transaction.
