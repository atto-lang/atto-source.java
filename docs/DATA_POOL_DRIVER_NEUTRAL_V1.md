# Driver-agnostic actor-owned data pools (design + stage-2 policy library)

Status (2026-10-20): **source policy + proposed trusted driver SPI**.
No production PostgreSQL/CockroachDB/Redis connections are implemented by
this document or its `std/data_pool` classes. Existing `std/db_pool` source
holds bounded local admission and protocol-session gates. Tests need
successful Maven/Graal/LLVM CI admission before shipping.

## One scheduler; different protocols

```text
Actor -- typed operation + opaque supervisor-issued capability --> DataRouter
              [authn/authz, bounded request & byte admission]
                                    |
                          PoolSupervisor / shards
                                    |
                 capability + workload + topology routing
                     /                |                \
               normal lane       transaction lane    dedicated lanes
                     |                |             blocking / subscription
                     +----------------+----------------+
                                      |
                          Driver-owned ConnectionActor
                                      |
                  adapter [pgwire, Cockroach pgwire, Redis RESP2/3]
                                      |
                            real TCP/TLS endpoint
```

Use a `StoreRef<Request, Result>` / typed actor capability as the stable
application-facing boundary (proposed API, not a currently compiled intrinsic).
**Do not expose physical sockets, credentials, protocol handles or transaction
leases across actor ownership boundaries.** Every connection owner has at most
one guest `state { ... }` field; access it only through verified state loans.
A message lifetime may suspend while the actor runs other messages, therefore
connection serialization needs a protocol gate independent of native thread
exclusivity.

## Shipped source policy

`std/data_pool` adds:
- `DriverCapabilities`: trusted adapter-declared support for transactions,
  bounded ordered pipelining/multiplexing, blocking operations, subscriptions,
  and topology routing. `can_serve` chooses normal (1), transaction (2),
  blocking (3), subscription (4), or control (5) lanes. Ordinary traffic
  alone can use shared pipelining; push/blocking operations never reuse it.
- `PoolPartition`: non-secret metadata to divide logical pools by driver,
  endpoint group, authenticated principal/authority, tenant, TLS/security
  profile, protocol scope (database/RESP mode), and workload lane. A guest
  `PoolPartition` is **never** proof of authority; only trusted supervisor
  allocations can select actual credentials/resources.
- `RetryPolicy`: bounded exponential backoff and conservative replay
  predicate. No retry unless a trusted adapter proves the entire operation
  definitely did not commit AND the complete operation is replay-safe.
  A transaction retry must rerun the *entire* transaction, not one failed
  statement. Unknown/ambiguous commit results must not be transparently retried.
- `shard_for`: deterministic nonnegative-hash pool affinity. This is **not**
  protocol-specific Redis Cluster hash-slot routing.

`std/db_pool.SessionGate` is local protocol admission accounting for an
ordered request-response connection, plus exclusive transactions/quarantine;
it does not parse frames, own a socket or prove database-side synchronization.
Its `begin_query` naming is historical, not an SQL-only constraint.
Special operations use dedicated lanes and driver-specific session state.

## Driver SPI: design contract, not yet an executable interface

Split adapter implementations into distinct protocol and flavor layers:

- `DriverFactory`: connect/handshake/TLS verification, negotiate actual
  capabilities, populate security scope and pool identity.
- `ConnectionCodec`: encode typed commands, incrementally decode bounded
  frames/responses and out-of-band push messages, enforce protocol sync.
- `OperationRouter`: select pool partition, lane, physical endpoint and
  topology generation. Never trust a caller's requested tenant/endpoint.
- `SessionCleaner`: full protocol-aware reset before reuse, or quarantine,
  close and replace the connection; failed cleanup must fail closed.
- `FailureClassifier`: normalized **KnownUncommitted**, **AmbiguousOutcome**,
  **PermanentFailure**, **AuthorizationFailure**, **TopologyChanged** and
  **ConnectionPoisoned** outcomes; retain engine-specific error codes.
- `RetryCoordinator`: attempt-budget, deadline, jitter, whole-transaction
  replay, idempotency/commit reconciliation and transaction lease release.
- `SnapshotStore`: CAS and supervisor fencing by actor generation and durable
  revision. Advertise durability only when the backend actually supplies it.

The future generic executor must support trait/capability **facets** rather
than pretending all backends understand SQL or identical transactions:
`KeyValueStore`, `SqlStore`, `TransactionalStore`, `PubSubSource`,
`DurableSnapshotStore`, etc. Unsupported operations fail at compile-time
when the facet is statically known, or at capability negotiation otherwise.

## Engine-specific obligations

**PostgreSQL:** PG extended wire protocol, row/result streaming, prepared
statements bound to a connection generation, TLS verified credentials,
transaction-local tenant settings and RLS, pipeline sync barriers, cancellation
and unknown-commit reconciliation. A PgBouncer transaction pool is an optional
external layer; do not leak session state across logical borrowers.

**CockroachDB:** can reuse the PostgreSQL wire codec, **not** blindly inherit
PostgreSQL error/retry/feature semantics. Handle SQLSTATE `40001` by bounded
whole-transaction replay when side effects are isolated and the transaction is
known uncommitted; `40003` and lost connections can be ambiguous and require
idempotency reconciliation. Preserve gateway/region/routing policy and
feature negotiation independently of PostgreSQL.

**Redis:** RESP2/RESP3 wire codec, bounded ordered command pipelining,
multiplexing, ACL/TLS, per-node Redis Cluster topology/slot ownership,
`MOVED`/`ASK` redirects, and per-node pool shards. Reserve separate
connections for blocking commands and Pub/Sub/push streams; do not let a
`BLPOP` or subscription block all ordinary callers. Account for RESP3
out-of-band push messages. Redis request acknowledgment is NOT necessarily
a durable snapshot receipt; durable actors require explicit storage semantics.

**Caches/supervisor:** snapshot watermarks and durable watermarks are distinct.
A cache mirror may be a replica, but may not silently become authoritative
for recovery. Use `std/actor_persistence.ReplicaCursor` for ordered replication
and a separately fenced durable store for persisted state.

## Security, performance and acceptance gates

1. Pool identity must include resolved credential, TLS policy, tenant,
   namespace/database, topology, and workload. The trusted router derives
   these from authenticated capabilities, not guest fields.
2. Bound count **and bytes** for queued requests, in-flight requests and
   protocol buffers. Maintain separately reserved control/close capacity.
   Request receipts are runtime-issued and released exactly once.
3. Maintain one mutable protocol-state owner per physical connection.
   Transaction leases are affine, scoped, and tied to pool generation.
   Stale or ambiguous sessions are quarantined, never lent again.
4. Use reactor-local connection ownership and NUMA-aware work routing;
   actor guest code remains single-threaded per actor but not pinned.
   Compare throughput and p99 latency with multiplex/pipeline depth,
   pool size, large results, cancellation and fair multi-tenant contention.
5. Fault-inject malformed frames, overflowed protocol buffers, stale
   topology redirects, cross-tenant requests, pool exhaustion, timeout
   races, commit ambiguity, owner crashes and reconnect epoch mismatches.
6. Run source tests `DataPoolStdlibTest` and `ActorDatabaseStdlibTest`,
   then driver/integration tests with PostgreSQL, CockroachDB and Redis
   across Java/Graal and LLVM. CI is currently not proven green.

Primary future implementation PRs: runtime generational opaque TCP/TLS handles;
trusted pool/router actors and receipt table; pgwire codec + PG/Cockroach flavor
policies; RESP2/RESP3 Redis codec + Cluster topology; snapshot CAS and retry
integration; native parity + latency/throughput evidence.
