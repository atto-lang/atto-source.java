# Typed durable scalar messages and atomic receipts — Java host slice

**Status:** implementation candidate; source-tree JUnit/AOT execution NOT VERIFIED.
Stack: `atto-source.java#1515` (SQLite), `#1523` (checked i64 scalar),
`#1530` (ActorRuntime bridge). Language and conformance proposals:
`atto-spec#65` and `atto-platform-conformance#66`.

## Boundaries actually added

- `DurableScalarSchemaV1` derives a stable nominal state/message schema ID,
  version 1, field tag 1, i64 wire type and SHA-256 descriptor fingerprint
  from the restricted, checked `PersistentScalarMirV1.Plan`. The schema
  identity is independent of the source-token code-generation fingerprint.
  No runtime reflection, JIT, foreign pointer serialization or guest codec
  fallback is used. General multi-field user schemas remain unsupported.
- New increment mail uses a canonical 48-byte big-endian wire envelope:
  `ATTO` magic (4 bytes), schema version (4), SHA-256 schema descriptor
  fingerprint (32), and signed i64 (8). Unknown versions or changed
  fingerprints reject before the actor's state turn. The exact legacy 8-byte
  i64 message decoder is a pinned **explicit v0 compatibility adapter**,
  not a general permissive decoder.
- `SqliteDurableActorStore.enqueue{,Fenced}` now binds each durable delivery
  ID to the **original payload bytes** under `BEGIN IMMEDIATE`. Same ID +
  different bytes is a typed failure; identical retries are nonmutating.
  Fenced enqueue rejects a stale activation epoch **before** reading
  the duplicate ID. Unfenced enqueue remains an authenticated host ingress
  responsibility; it does not grant actor-origin authority.
- `commitTurn` writes an immutable per-delivery result snapshot and
  SHA-256 original-command binding to `durable_command_receipts` in
  **the same SQL transaction** as consuming the inbox head, state CAS and
  outbox-intent insertion. An outbox uniqueness conflict rolls all four
  changes back. `committedDelivery` returns the original committed snapshot,
  only after an active-epoch fence and original-byte verification; pending
  commands return empty, not success. Old stores get the additive receipt table
  on open; past already-consumed commands have no retroactive fabricated
  receipts.
- The checked scalar actor now returns an original committed i64 result for
  exact duplicate commands, even after recovery. It cannot reapply a command
  to state or create a second outbox row. A conflicting duplicate poisons
  the current scalar activation. A general typed `Result` receipt API and
  bounded idempotency retention/expiry policy are future contract work.
- `AsyncSqliteDurableProviderV1` opens and executes JDBC on one bounded
  host-owned daemon worker. Calls return completion stages immediately;
  exhausted queues reject, never park the requesting actor carrier.
  `closeAsync` drains admitted operations before closing the connection.
  **Do not call `.join()` / `.get()` on actor carriers.**
- **New opt-in async execution route**: trusted host code calls
  `AsyncPersistentScalarCodeExecutorV1.prepare(...)` to activate and replay
  a bounded, verified inbox on the isolated SQLite worker, then calls
  `ActorRuntime.spawnPreparedPersistentScalarActor(...)`. Its
  `initializeActor` creates private scalar state without disk I/O.
  Each scalar increment/read captures an actor turn, submits fenced SQLite
  operations, and invokes `ActorRuntime.suspendCurrentUserMailboxUntil`
  on `OresFuture.from(stage)`. `enqueueOnCompletion` publishes state
  and result only through an actor-affine continuation before the suspended
  user mail is marked processed. Incoming user mail stays FIFO.
  **This is a separate opt-in profile**, not a silent behavior change to
  `PersistentScalarCodeExecutor`, which still executes synchronous JDBC.
  The I/O provider refuses to execute SQLite open/operation/close from
  an actor-carrier thread, and its admission counter is never held across
  database close or completion notifications. Host continuations registered
  on completion stages must remain nonblocking so they do not monopolize
  the single database worker.
- **Preparation/STOP caveats**: the supervisor must quiesce external ingress
  while preparing (otherwise a later unconsumed inbox record correctly
  prevents newer turns from leapfrogging it). Source readiness follows the
  prepared snapshot, but a concurrent newer activation may invalidate its
  epoch before the first mail; per-turn fences reject it. Graceful teardown
  queues nonblocking hibernation, but its `ActorRuntime` STOP future does
  **not** currently certify the durable hibernation receipt. Pending outbox
  intents fail closed before prepare; generic actor handlers are unsupported.
  The async provider is host-only until scheduler continuation wiring exists.

## Current strict limitations

0. The async route uses the **existing** source mailbox suspend/resume
   mechanism, not a new scheduler. `AsyncPersistentScalarActorRuntimeTest`
   targets actual SQLite, FIFO continuations, duplicate responses, cold
   reopen and stale epoch fencing. Exact-head JUnit runs have not yet
   executed steps. Crash-after-commit/receipt-loss faults still require an
   admitted runner and dedicated subprocess stress fixtures.

1. The verified source compiler currently recognizes **one i64 state field**
   and only `increment`/`get_count`; it does not parse or type-check general
   state/message migrations. V2 schemas are rejected; copy-then-switch and
   migration replay across code-generation changes remain pending.
2. `PersistentScalarActorV1` refuses a pending nonempty outbox on activation.
   There is no leased outbox dispatcher, remote-ack reconciliation, or
   exactly-once downstream effect guarantee. Local outbox insertion is atomic
   with the state turn, but network delivery remains outside that boundary.
3. No compiler-backed generic durable handler, actor-loop async suspension,
   source guest full-AOT proof, or Java/LLVM/.NET/Wasm/Forge wire parity is
   certified by these changes. Do not convert compiler `UNSUPPORTED`
   outcomes into host fallbacks.

## Verification gates

Focused exact-head workflow:
`.github/workflows/durable-typed-scalar-exact-head.yml` (JDK 25,
real SQLite JUnit). Tests include:
- `DurableTypedScalarAotTest`: generated descriptor and versioned wire,
  incompatible version/fingerprint rejection, bounded async provider and
  single-store transaction/readback;
- `SqliteDurableActorStoreTest`: duplicate identity conflict, atomic
  receipt/outbox commit, rollback and reopened-store replay;
- `PersistentScalarActorV1Test`: original committed result after a retry,
  explicit legacy inbox replay and poison on conflicting reuse;
- `PersistentScalarActorRuntimeBridgeTest`: actual ActorRuntime mailbox
  integration and cold-process recovery as an existing source test;
- `AsyncPersistentScalarActorRuntimeTest`: new opt-in prepared async actor
  execution, FIFO mailbox suspension, retries returning original receipts,
  replayed legacy/versioned inputs, old-epoch revocation, and
  `AsyncPersistentScalarRuntimeProcessMain`'s unclean `Runtime.halt(0)`
  immediately after a commit/output followed by a fresh JVM process recovery.

**Evidence boundary (2026-11-21):** The exact
`DurableScalarSchemaV1.java` Git blob
`e97f8cf3ca2fead79db8ecb2db500af0d312b133` compiled under local
JDK 21 with `javac -Xlint:all -Werror`, and standalone typed wire
roundtrip/version/fingerprint rejection checks passed. The exact
`AsyncSqliteDurableProviderV1.java` Git blob
`612ab1f882a0e03c2f48d9140f669912140a0ed8` compiled under local
JDK 21 against *stand-in DurableActorStore/SqliteDurableActorStore classes*;
the stubbed worker-isolation, completion and close checks passed. These
are **exact-blob component tests, not source-tree JUnit**. The independent
SQLite DDL/transaction probe passed *HOST SQL MODEL* checks for duplicate
identity, rollback atomicity, state/outbox/receipt commit. Shared golden
wire vectors are verified by Node host tests at `atto-platform-conformance#66`
(2/2, source and fixture Git blob SHAs matched). No compiler-backed runtime
schema migration, full SQLite/JUnit, Native Image or guest AOT is verified.

GitHub Actions exact-current-head jobs failed with **zero executed steps**:
`NOT_EXECUTED`, not compiler failures and not passes. No private
cross-repository source mirror is authorized by the current export gate
(`docs/PRIVATE_CI_MIRRORS.md` / `.github/private-ci-carrier-contracts.json`)
for this PR. It must not be bypassed.

## Next implementation gate

Wire the async provider's `CompletionStage` into real ActorRuntime
suspend/resume turn ownership without a blocking wait, then add compiler
validated adjacent-schema migration plans with copy-then-switch/rollback and
a fenced at-least-once outbox relay. Only then promote this restricted
candidate toward full guest durability and cross-backend parity.
