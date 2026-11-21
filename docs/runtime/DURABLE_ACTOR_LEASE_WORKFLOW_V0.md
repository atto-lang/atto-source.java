# Durable actor admission, webhook and outbox: host v0

**Status:** implementation draft, tested as isolated host/JDBC code against PostgreSQL 16. **Not connected to Oreslang guest `ActorRuntime`**, not verified with complete GraalVM compiler CI.

## Apply the SQL before deploying

Run `docs/runtime/sql/durable_actor_v0.sql` under an operator-controlled migration. It is idempotent for a fresh v0 database and uses `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for older databases with the original actor/outbox columns. **Stop old process writers before this cutover**: the old runtime ignores lease columns and cannot safely coexist with the new lease/claim protocol. Rollout still requires verified migration, rollback and data-retention plans.

## Activation and takeover

`acquireLease(address, ownerUuid)` atomically locks the actor record and rejects a healthy owner. Lease expiration uses **PostgreSQL's clock** (30 seconds), not host clock. `renewLease(address, epoch, ownerUuid)` cannot revive an expired owner; `hibernateLease` requires the token and an unexpired lease. The legacy `activate(address)` also respects the healthy-owner gate.

An expired owner may still be executing guest callbacks after it loses its DB lease. Database fencing prevents a stale turn from **committing** state and outbox, but this alone does **not** establish actor-wide physical execution exclusivity or stop external side effects already issued. The guest runtime needs a CONTROL admission gate, lease renewal lifecycle and supervised eviction.

## Atomic authenticated webhook admission

Node verifies `X-Hub-Signature-256` on the original request bytes, validates event, size, ref, SHA, repository and installation, then invokes one `admitSignedPush` callback. It does NOT accept untrusted tenant/run IDs or perform a read-then-insert pair.

The trusted host must authorize a specific `DurableActorStore.Address` for the GitHub App installation and call `PostgresDurableWorkflowGate.admitSignedPush`. That PostgreSQL transaction locks the actor row, validates the stored expected ref/SHA/repository/installation and actor state version, atomically deduplicates the delivery, enqueues bounded mail, and changes the expectation to `done`. Replaying the same delivery is a harmless duplicate; **a second delivery ID for the same expected push is stale**.

The Node and Java layers are not yet connected across their actual host process boundary. No production installation-to-tenant resolver exists in this PR.

## Outbox operations

`claimNext(address, epoch, ownerUuid, claimUuid)` checks the current unexpired actor lease and claims one pending outbox intent for 60 seconds using the database clock. A competing claimant cannot take that intent until claim expiry. External GitHub/CI/AI operations must run **after** the claim transaction is committed, retaining the intent's stable idempotency key. `recordReceipt` rejects old epochs, owners and claim tokens and persists a receipt.

**At-least-once recovery, not exactly once externally:** a crash after a GitHub operation succeeds but before receipt storage may cause a retried operation. The dispatcher must query the upstream service and reconcile results using the original idempotency key, not blindly rerun a non-idempotent operation. Dispatchers must support cancellation, limits, backoff and dead-letter handling before production use.

## Test scope

Scoped private GitHub Actions runs pin each source Git blob SHA, compile the Java adapter + standalone tests, run a disposable PostgreSQL 16 service, and execute the signed-webhook Node tests. Tests include healthy-owner refusal, forced lease expiry and recovery, same-logical-push replay with two delivery IDs, stale receipt fencing, claim-token expiry and retry. **Not tested:** real GraalVM guest actor scheduling, heap serialization, hot-reload compatibility, signed snapshot digest, full JUnit suite, real GitHub operations, and cross-language live process integration.
