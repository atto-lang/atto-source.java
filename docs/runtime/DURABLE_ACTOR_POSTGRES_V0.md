# PostgreSQL durable actor store — implementation v0

This adapter extends the draft `DurableActorStore` API from Java PR #1370. It is **not** yet registered as an `ActorRuntime` component or exposed to Oreslang guest actors.

## Deploy

Apply `docs/runtime/sql/durable_actor_v0.sql` through an operator-controlled migration. Construct `PostgresDurableActorStore` with an application-provided `javax.sql.DataSource` backed by PostgreSQL JDBC (the driver is deliberately **not** required by the compiler/runtime module). All writes use parameterized SQL and per-actor row locks.

The adapter bounds each actor to 256 unconsumed inbox messages and 256 unacknowledged outbox operations by default, with a separate 32 MiB byte budget per queue. Quota checks run while the actor database row is locked, including when accepting mail for a dormant actor. Retried delivery IDs remain harmless even at capacity; exceeding the quota returns a fail-closed admission error, not silently dropped work. A caller should translate this to backpressure and retry with the original delivery ID rather than fabricate a new ID. Each pending message is additionally capped at 16 MiB. Historical consumed/delivered records are retained for deduplication; bounded retention/compaction is still needed before production use.

The host may `initialize(address, snapshot-v0)` exactly once, then `activate(address)`; activation **always increments a durable epoch** (including after an unclean JVM crash), fencing earlier incarnations. `enqueue` admits mail while dormant. `commitTurn` consumes one inbox message, updates a versioned snapshot, and inserts outbox intents in **one PostgreSQL transaction**. A duplicate outbox intent rolls back the whole transition. `acknowledge` persists receipts after a real external result, not during guest execution.

## Safety and non-goals

- The record identifies the tenant, workflow, run, and node. Authorization of that identity is the calling host's responsibility; a valid ID alone is **not** a capability.
- An older runtime may still compute on its carrier after another node steals the activation epoch, but **cannot commit** a stale state/outbox transition. The scheduler must separately provide actor-wide guest execution exclusivity and quiescence.
- Database transactions do not guarantee exactly-once external GitHub/LLM/CI side effects. Dispatchers need idempotency keys, durable receipts, reconciliation, and bounding.
- Snapshot **byte encoding, cryptographic digest, codec upgrades and state/code migrations are not implemented**. Format/schema/generation mismatches are rejected during turn commit. The common-source canonical digest contract remains pending implementation in the guest codec.
- Native file handles, sockets, Java continuations, promises, borrows and private heap pointers must not be serialized. Only explicit application-owned encoded state belongs in `Snapshot`.
- Timers, event log and state-machine definition upgrades are outside this v0 adapter. No automatic merge/deployment policy is provided.
- `activate` deliberately permits fencing takeover of an older epoch. Production placement should add heartbeat/lease expiry or supervised takeover admission before activating an apparently healthy actor.

## Tested behavior

`tools/durable-actor-test/PostgresStoreSmoke.java` runs against a disposable PostgreSQL 16 service, using only a test-local credential. It exercises restart by reconstructing a fresh adapter against the same database, inbox deduplication, transactional rollback on outbox conflict, receipt conflict and stale-epoch rejection.

Validation must run at an **exact pinned source commit**. The private CI carrier tests the scoped adapter and SQL files, not the full GraalVM runtime or JUnit suite. Keep that distinction in PR review.

## Exact source hardening evidence

Private runner result: `ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra` Actions run `39164754801` verified eight exact source Git blobs and executed ten webhook tests, the restart recovery smoke and the concurrent Postgres quota regression. The quota regression exercises eight simultaneous writers to one mailbox credit, idempotence at capacity, rollback when an outbox is full, and credit restoration after a stored receipt. These host tests are not yet full actor compiler/runtime coverage.
