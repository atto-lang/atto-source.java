# Actor database infrastructure: Oreslang stdlib stage 1 (2026-10-20)

Status: **Oreslang source-level policy building blocks**, not a live PostgreSQL,
CockroachDB or Redis driver, or production connection pool. The generic
adapter architecture and additional policy module are specified in
`docs/DATA_POOL_DRIVER_NEUTRAL_V1.md`. The classes are packaged with the Java
reference interpreter. LLVM/native parity, async actor integration and database
integration remain work to be implemented and validated.

## Packaged imports

- `std/db_pool`: `PoolLimits`, `AdmissionBudget`, `SessionGate` and factories.
- `std/actor_persistence`: `WritePlan`, `Cursor` and factories.
- `std/data_pool`: driver capabilities, pool partitions, retry policy and shard affinity.

These classes deliberately do not contain credentials, native pointers,
PostgreSQL sockets, SQL strings, database security principals, or actor refs.
A `SessionGate` is private state inside the trusted connection owner; a
`Cursor` is private state inside the owning actor. Neither is a transferable
capability. Never use the boolean results of these methods as authorization.

## Intended architecture

```
actors -- typed requests --> trusted DbRouter / operation allowlist
                                    |
                          bounded admission + tenant RBAC
                                    |
                        shard / transaction scheduling
                                    |
                         owned PgConnection actors
                                    |
                         checked protocol + TLS
                                    |
                               PostgreSQL
```

1. Supervisor supplies *unforgeable* database-operation capabilities, not a
   connection ID. Untrusted actors receive only typed allowlisted operations.
2. The trusted router independently validates actor identity, target database,
   tenant, operation, request byte count, and deadline. These are **not**
   established by `WritePlan.actor_id` or any guest-controlled field.
3. Router issues runtime-owned request receipts. Each receipt records its
   immutable byte size, logical owner, pool generation and settlement state.
   The single owner invokes `AdmissionBudget.release` exactly once, using
   receipt metadata rather than a caller-supplied amount. Quotas also apply
   before queue insertion; reject if full.
4. A `PgConnection` actor owns one physical socket and all PostgreSQL
   protocol state. `SessionGate` allows bounded ordered pipelining, an
   exclusive transaction, or quarantine. It **does not** parse PostgreSQL
   frames; a real driver must correlate responses, synchronization points,
   cancellations and deferred errors before passing `protocol_synced=true`.
   Transaction cleanup must verify rollback/commit and session reset.
5. Never reopen a quarantined session for reuse. Close, release all receipts
   safely, and establish a new session under a new generation. Transaction
   state cannot be transferred from one TCP connection to another.
6. For prepared statements use pinned operation IDs/allowlists, parameter
   binding, and driver-side statement caching keyed by connection generation.
   The router must not accept arbitrary SQL from untrusted actors.
7. Database role privileges and PostgreSQL RLS provide independent
   enforcement; tenant/session settings must be transaction-local and reset.
   Distinct privilege principals require separate pools. Transport must
   authenticate PostgreSQL TLS server identity.

## Snapshot contract

Within a verified critical actor state loan, copy/freeze the actor's state
and record a new revision, then release the loan *before* network I/O.
`Cursor.mark_changed()` increments the local revision.
`Cursor.begin_flush()` returns the revision to persist (0 means no work);
the storage adapter derives `WritePlan.expected_version` from the durable
watermark, and assigns an idempotent operation ID. Revisions may coalesce.

In PostgreSQL a write must use a *single atomic conditional* operation that
validates the actor's supervisor-issued generation/fencing token **and** the
stored expected durable version. Write plans alone are untrusted metadata.
Generation ownership must be established by a distinct trusted transaction
before a newly restored actor can write; the adapter must never infer a
generation transition from a plan. Inspect affected row counts and reject
stale writers.

`Cursor.ack(generation, version)` only follows a verified commit receipt.
An unknown outcome after a transport failure is not a verified failure:
probe the durable idempotency key, reconcile the authoritative version, or
keep the flush outstanding. Only then may `Cursor.retry` reset a pending
flush. A later local state revision must not be reverted by an old ack.

Consistency modes (future library): best-effort checkpoint, awaited durable
snapshot, and command/outbox-based durable-before-reply. Cache replicas and
supervisor mirrors are not independent concurrent writers unless an explicit
merge/conflict contract is supplied.

## Non-durable supervisor and cache mirrors

`actor_persistence.ReplicaCursor` records the last applied *replica*
revision, not the durable database watermark. A complete immutable snapshot
can advance the version across skipped revisions; a delta is accepted only if
its revision is exactly the successor to the replica's current version.
Mismatched actor generations, replayed messages and delta gaps are rejected
without modifying progress. A new generation requires a newly authenticated
replica cursor. This class only checks ordering metadata: the receiver must
authenticate the sender/actor identity, verify payload integrity, and apply
the state update atomically with the watermark. Never use replica progress
to acknowledge durable PostgreSQL writes.

## Missing implementation and acceptance gates

- Introduce an opaque generational resource table for TCP/TLS connections.
- Implement an actor-safe nonblocking PostgreSQL extended-protocol client,
  bind parameter encoding, authentication, cancellation, pipeline barriers,
  startup/session resets, and multiplexed request-result correlation.
- Add trusted `DbRouter`, `PgConnection`, `PgTransactionWorker`, and
  `ActorPersistenceService` actors using exactly one guest-declared `state`
  schema each, accessed exclusively through verified state loans.
- Integrate concurrency and byte admission, per-tenant fairness and bounded
  wait queues, deadlines, circuit breaking, shutdown, and request accounting.
- Verify PostgreSQL conditional writes + idempotency receipts against a real
  server, including uncertain-commit retries, stale generations and crashes.
- Run Maven `-Dtest=ActorDatabaseStdlibTest test` and repeat under LLVM;
  check cancellation races, impersonation, exhaustion, and multi-tenant RLS.

No socket may cross the actor transport boundary; a shared database-service
ref does **not** imply shared mutable connection ownership.
