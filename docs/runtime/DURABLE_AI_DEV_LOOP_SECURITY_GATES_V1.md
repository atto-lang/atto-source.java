# Durable actor security gates v1 (experimental)

Scope: host-only PostgreSQL adapter and webhook/outbox control plane. This is
**not connected to Oreslang guest execution, GraalVM scheduler quiescence, or
Java actor-owned arenas** and does not prove network exactly-once side effects.

## Activation admission and fencing

- The PostgreSQL actor row stores `lease_owner`, `lease_expires_at`, and a
  monotonically increasing `activation_epoch`. A healthy or unverified lease
  is not eligible for takeover. A new activation is possible only when
  `active=false` (graceful hibernation) or the DB clock says the old lease
  is expired. Crash tests simulate expiration by modifying fixture rows.
- The adapter generates a random owner identity. `renew(address,epoch)` only
  extends a still-live owned lease. Commits, receipt writes and hibernation
  reject stale, expired, or wrong-owner epochs.
- This is **database commit fencing**, not physical single-executor isolation.
  A crashed or partitioned worker can still execute guest code after expiry.
  Scheduler admission, watchdogs, isolate/worker teardown, and suspended
  callbacks must be coordinated before production use. In particular,
  `DurableActivationCoordinator.restore` does not yet enforce a distributed
  quiescence proof or distinguish supervised takeover authorities.
- The experimental lease is hard-coded to 60 seconds. A production scheduler
  must run supervised renewal and halt dispatch if renewal fails. It must not
  assume the duration guarantees permission to execute a long-running turn.

## Signed GitHub webhook admission

- `src/ai-dev-loop-webhook.mjs` in conformance PR #106 validates the raw-body
  HMAC and then calls exactly one backend function: `admitVerifiedEvent`.
  It no longer performs a separate `readExpected` followed by `persistInbox`.
- `PostgresDurableWorkflowGate.accept` is the database-side atomic route:
  authorized tenant, installation, repository, SHA, ref, actor state version,
  durable delivery ID, inbox budget and insertion in a single transaction.
  Expectation staging and admission both lock actor before expectation.
- Caller **must** resolve `authorizedTenant` through a trusted GitHub App
  installation/tenant mapping. Neither the signature nor the event JSON alone
  is tenant authorization. The Node module and Java DB adapter are not yet
  connected by an HTTP/IPC service; wire them through an authenticated host
  boundary, never by trusting tenant IDs in webhook JSON.
- `stage(expected)` is a separate privileged host method. The workflow
  `waiting_webhook` transition and expectation staging must ultimately share
  one transaction to eliminate staging/state-transition crash gaps.

## Durable external-operation claims

- `claim(address, epoch, ttl)` leases one pending outbox intent with a fresh
  opaque claim token, increments attempt count, and never issues network IO.
- `finish(claim, receipt)` atomically verifies the active actor epoch, claim
  token and claim deadline before recording the durable receipt.
- Claims provide **at-least-once dispatch**, NOT exactly-once external side
  effects. Use the stable `idempotencyKey` when talking to GitHub/CI/AI
  providers and reconcile by provider receipt before retrying. A late response
  for an expired claim may have succeeded at the provider; ignoring that fact
  and dispatching blindly can duplicate an operation.

## Coverage

The private, exact-blob CI carrier builds these Java host classes against
PostgreSQL 16 and runs three smoke programs: restart/fencing, concurrent
mailbox/outbox quota, and verified webhook/claim races. Node host tests cover
raw-body HMAC and fail-closed ingress. This does not replace Java 21 Maven,
JUnit, GraalVM guest runtime, distributed worker, network partition, or
cryptographic snapshot codec testing.

## Second hardening audit: cross-actor delivery and owner binding

- Outbox claim/finish now receive proof of the **current actor store's lease
  ownership** through a package-private transaction-bound check. An epoch
  number alone is no longer accepted as dispatch authority. Host constructors
  must pair the workflow gate with the owning `PostgresDurableActorStore`.
- The `ores_durable_github_delivery` table records each GitHub App installation
  and delivery ID independently of whichever workflow happened to be waiting.
  Identical replay is idempotent even after workflow routing changes; conflicting
  metadata for a reused ID fails closed. The ledger insert is in the same
  transaction as inbox admission, so it rolls back with a failed enqueue.
- Installations and tenant mappings still require a trusted authenticated
  backend; no service boundary has been deployed. Keep the delivery ledger
  long enough to meet the intended replay window; historical growth needs a
  documented retention and archival policy.

### Verification status for this revision

Earlier scoped PostgreSQL run #39170003476 was green for the *preceding*
revision. The new owner-bound dispatch and installation-wide delivery changes
have added regression tests in `tools/durable-actor-test/PostgresWorkflowGateSmoke.java`,
but a connector source-transfer restriction prevented sending all updated
SQL/test files to the alternative private CI repository. **Do not attribute
that older green run to these new changes.** Exact-head database execution is
still outstanding until source-side Actions are funded or an authorized CI
mechanism can run the full updated test.
