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
