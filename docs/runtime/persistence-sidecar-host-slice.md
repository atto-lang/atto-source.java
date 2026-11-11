# Batch persistence sidecar — Java host integration slice

**Status: host adapter implementation, NOT a compiled .ores PersistenceSidecar actor and NOT production durable storage.** Reference design: https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/227

- BatchPersistenceSidecar owns a bounded tenant queue, separate storage worker, per-actor/slot write metadata, scheduled or manual flush and CAS/fencing provider contracts.
- **Durable receipt proof:** `BatchPersistenceSidecar.Store.verifyDurableCommit(write, receipt)` is fail-closed by default. Before a durable success escapes the storage worker, the sidecar checks the expected version and asks the trusted provider to attest the *exact* operation record. `SqlitePersistenceStore` checks tenant/actor/state/op ID, epoch, version, schema, kind, original payload bytes and the current fence in one SQLite read transaction. A fabricated `COMMITTED`/`DUPLICATE` receipt or unexpected version is `OUTCOME_UNKNOWN`, not permission to publish actor state. MemoryStore intentionally cannot claim durable success. This is a host-provenance boundary, not authentication of a compromised storage provider.
- **Commit-before-publication regression:** the real ActorRuntime host fixture now keeps the proposed `long` in actor-local turn variables while its owned serialized snapshot crosses the asynchronous sidecar; it writes `PrivateMemoryBlock` only in the actor continuation after the verified durable receipt. A separate trusted actor fed a forged durable Store receipt must remain at its original value and observe a failed continuation. Neither test is a compiled `.ores` actor or a JDK/JDBC CI pass until executed on the exact source tree.

- Identity and operation names are NFC-normalized, valid UTF-8 scalar strings; malformed unpaired surrogates and control characters fail closed. Queue admission reserves fixed envelope overhead, payload bytes, and variable UTF-8 actor/state/operation identifier bytes (a conservative budget, not total JVM heap accounting).
- `SqlitePersistenceStore.loadFenced(id, trustedEpoch)` is the **actor reactivation** read API: it verifies the current epoch and reads committed state within one SQLite snapshot transaction. An unfenced `load(id)` remains a privileged host inspection/migration operation and must never be lowered as an actor guest API. The new source actor incarnation must not publish restored state before the fence check succeeds.

- The owned Write record defensively copies payload bytes; raw guest references do not cross an actor/sidecar boundary.
- MemoryStore is deliberately **volatile**, for contract tests only. Receipt \`durable=false\` even for COMMITTED and DUPLICATE. A production durable Store must confirm provider commit transaction and set \`durable=true\` only when justified.
- ActorPersistenceBridge uses real ActorRuntime.OresFuture and continuation APIs. Guest code must never invoke store callbacks on the storage thread. The bridge is **not wired into the Oreslang parser/guest source lowering yet**.
- Trusted host must establish authenticated tenant ID and epoch, derive serialized actor-owned state only through sealed-state access, enforce permissions and sidecar quota, and ensure CAS/dedupe/fencing in the same durable provider transaction. A caller-supplied epoch is not trusted authority.
- A rejected or timed-out provider write may already have committed; the adapter surfaces OUTCOME_UNKNOWN. Retry must reuse operation ID.
- Future completion only settles an OresFuture. The scheduler owns guest await/resumption. Cancellation may not cancel an already-committed external effect.
- Host-only contract tests: \`bash scripts/test-batch-persistence-sidecar-host.sh\`; these compile on JDK 21 without dependency on Truffle. The real bridge must compile separately with the full Maven/Graal runtime.
- Missing: native source actor API, durable store connector, authenticated serialization, 10k-actor fairness stress, actor-owned arena drop/fencing, distributed placement/shards, full Maven/Native Image execution, crash/recovery tests and CIL/Wasm/Cranelift backend parity. Do not promote host CI to native Oreslang guest conformance.

## Java host adapter evidence

Locally compiled with OpenJDK 21 using \`javac -Xlint:all\`; 11/11 tests passed through 30 repeat runs after activation-fencing and identity-hardening changes. This does NOT prove GitHub Actions runner admission or the entire Maven build. A separate stub-signature compile checks ActorPersistenceBridge against API shapes; exact native ActorRuntime classes require the full backend build.

## Security audit (2026-10-29)

- Deduplication now follows the provider epoch fence, so an old activation cannot obtain a prior commit receipt after migration; retries under the new epoch with reused operation IDs fail as conflicts.
- Epoch ownership uses a typed `(tenant, actor)` record, not a delimiter-joined string. Tenant/actor/state/operation IDs reject ISO control characters including NUL.
- In-flight requests retain admission quota while the storage worker is committing. A blocking-store regression proves no extra requests can bypass the outstanding quota.
- Local JDK 21 host contract: **11/11 passed, 30 consecutive runs**. The dedicated Java PR GitHub Actions run currently failed with zero recorded steps, so runner-executed test evidence remains NOT_EXECUTED. Full Maven/GraalVM and source actor integration remain unproven.


## SQLite WAL durable provider — 2026-10-29

`SqlitePersistenceStore` is an actual on-disk JDBC SQLite provider implementing `BatchPersistenceSidecar.Store`. It uses `PRAGMA journal_mode=WAL`, `synchronous=FULL`, transaction-scoped per-actor fencing, per-state CAS, durable operation deduplication, snapshot recovery, and ordered event replay. `BatchPersistenceSidecar` itself runs storage work on a dedicated host worker; do not call the blocking JDBC store from an Oreslang actor execution carrier. The database path is trusted host configuration, never a guest or tenant-controlled string.

The SQLite file schema matches the native LLVM host provider (`fences`, `state`, `ops`, `events`) but **cross-backend file interchange has not yet been executed**. Journal-first state must have snapshot_version=0, snapshot_schema=0 and no snapshot bytes.

- **Verified locally:** JDK 21 `javac -Xlint:all -Werror` compiled the real JDBC provider and real-database test main. The Maven Central SQLite JDBC driver is not available in the local test environment; **no Java JDBC database execution happened locally**.
- **CI prepared, but not yet executed:** `.github/workflows/persistence-sidecar-sqlite-durable.yml` fetches pinned `org.xerial:sqlite-jdbc:3.53.4.0` and `slf4j-api:2.0.17`, compiles sources with strict flags, and runs actual 1,000-actor snapshot/reopen/event/epoch/process-halt tests. Only a nonzero-step runner can validate this.
- This is not compiled Oreslang `.ores` guest persistence, actor arena reclamation, native GraalVM Image validation, nor a secure Graal/OS tenant isolate.
- Required next step: compile and execute an authentic `.ores` actor via the native compiler, pass serialized state to the trusted bridge, await provider-confirmed receipt, deactivate and reconstruct into a fresh arena. Enforce tenant and epoch using host-side directory authority.


## Authentic Java ActorRuntime scheduling and actor-private memory integration

Added `src/test/java/dev/oreslang/runtime/persistence/ActorRuntimePersistenceIntegrationTestMain.java` as a **full runtime integration target** (not a fake actor model). It creates a private actor with the real `ActorRuntime.spawnPrivateTrusted` host admission, allocates an owner-checked `PrivateMemoryBlock`, mutates a balance during a serialized mailbox turn, uses `ActorPersistenceBridge` to enqueue a snapshot through `OresFuture`, receives the provider receipt via the real actor continuation lane, and terminates. A new `ActorRuntime` and actor allocation restore state from the on-disk SQLite snapshot, with a new activation epoch. The test asserts stale heap reclamation and stale-epoch denial.

The bridge now binds an immutable **activation capability** to the actual current runtime actor ID, a trusted stable `(tenant,actor,state)` identity and activation epoch. Guest call sites can pass state bytes and operation ID only through that owned binding; they cannot supply an arbitrary `BatchPersistenceSidecar.Write` to this bridge. The trusted VM/supervisor must authenticate the tenant/epoch and install the provider fence *before* minting a binding. The host bridge is NOT a real standalone sandbox, and guest Java interop must remain denied absent explicit permissions.

The test is intentionally run only under full Java/Truffle dependency compilation. Workflow `.github/workflows/persistence-actor-runtime-integration.yml` builds the project, obtains pinned JDBC dependency, and runs the exact `ActorRuntime` program. **Test NOT_EXECUTED:** source-org GitHub Actions fails before step 1, Maven tooling/third-party dependencies are unavailable in the local sandbox. This is not an Oreslang `.ores` guest compiler test; genuine guest source lowering into this bridge remains a P0 blocker.


## Event-journal bridge hardening (November 10, 2026)

A trusted, actor-incarnation-bound `ActorPersistenceBridge` now offers
`submitOwnedEvent` and `onEventCompletionInCurrentActor` in addition to
snapshot calls. Both issue `Kind.EVENT` writes containing copied serialized
event bytes through the same bounded async sidecar and actor continuation lane.
The bridge never exposes a raw `Write`, host `Store`, tenant grant or
activation epoch to guest code.

`ActorRuntimeEventJournalIntegrationTestMain` is a **host ActorRuntime**
scenario using true actor-private memory, the real SQLite journal, verified
commit-before-publication, stop/recreate with a fresh actor heap, ordered replay,
durable op-ID deduplication and provider-side stale-epoch denial. The existing
actor-runtime workflow now invokes both the snapshot integration main and
event-journal integration main after full Maven test compilation and loading
pinned JDBC dependencies.

**Verification status: NOT_EXECUTED** for this exact new branch. The Java event
fixture is neither a compiled `.ores` guest nor a process-kill test; it cannot
certify source-guest event sourcing or GraalVM Native Image support. The shared
conformance host-reference SIGKILL fixture lives in
`reference/persistence-sidecar/bank-account-crash.test.mjs` in the conformance
hardening branch. Those host tests must also run before claiming their passes.

**Next language/compiler work (P0):**
1. Specify an `EventSourcedActor<State,Command,Event>` type and introduce
   typed `#event_sourced` metadata via existing actor parser/typechecker.
2. Lower compiled actor-owned immutable event bytes to the trusted
   `ActorPersistenceBridge` without an exposed epoch or raw host provider.
3. Prove negative source tests for sealed state, critical-loan escape, impure
   reducers, nonserializable payloads and await/select across mutable loans.
4. Run a **real** compiled guest actor through journal commit, restart, replay
   and SIGKILL-before/after-commit receipts, with exact-head stepful provenance.
5. Extend the same source fixtures to LLVM, .NET Native AOT, Wasm and Forge;
   treat Cranelift separately from Forge.


## Atomic event-sourced command bridge — 2026-11-10

**Status: DRAFT HOST IMPLEMENTATION. Java source-guest lowering and exact-head execution NOT_VERIFIED.**

Conformance contract: [PR #668](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/668)
(stacked on #666/#231/#227). Unlike the earlier one-event `Kind.EVENT`
sidecar, an event-sourced logical command may produce **multiple immutable
events** and one stable command result.

New additive host classes:
- `SqliteCommandTransactionStore`: a trusted JDBC provider sharing
  `fences`, `state`, `events`, and `ops` tables with
  `SqlitePersistenceStore`. It creates a distinct `command_commits`
  logical-command index, independent of activation epoch. Under
  `BEGIN IMMEDIATE`, it validates epoch first, checks command hash/op ID,
  enforces expected-version CAS, atomically appends all event payloads and
  the new stream head, and commits the stable command-result record.
  Durable duplicate proof checks event bytes hash, result hash, stream
  version/count and snapshot barrier. The store NEVER runs on an actor
  carrier; only its dedicated host worker may use blocking JDBC.
- `AtomicCommandSidecar`: bounded tenant ownership, defensive byte copies,
  serial worker, asynchronous future and **second trusted operation lookup**
  before a COMMITTED/DUPLICATE success reaches the actor continuation.
  Backpressure is not durability. If the worker cannot settle at close,
  accepted pending futures become `OUTCOME_UNKNOWN`, not silent success.
- `ActorPersistenceBridge.submitOwnedCommand` /
  `onCommandCompletionInCurrentActor`: actor incarnation capability
  supplies trusted tenant, stable identity and epoch; guest receives neither
  the database path nor raw host Store/epoch.
- `SqliteCommandTransactionStore.loadVerified`: epoch fence, head,
  snapshot, bounded event replay and both single-event and logical-command
  integrity are read in ONE SQLite read transaction. Missing, overlapping,
  tampered, noncontiguous, too-large, and schema-inconsistent replay rows
  fail before state publication.

New host test mains:
- `SqliteCommandTransactionStoreTestMain`: JDBC multi-event commit,
  2nd-event SQL trigger rollback, epoch2 dedup, command-ID conflict,
  caller-buffer ownership, compaction barrier, altered event hash.
- `ActorRuntimeAtomicCommandIntegrationTestMain`: real
  `ActorRuntime.spawnPrivateTrusted`, private actor arena, continuation
  on owner lane, commit-before-publication, new runtime/arena replay,
  epoch2 retry, and stale epoch rejection.

Host workflows now run strict JDK 21 `javac -Xlint:all -Werror`
with pinned JDBC dependencies and the new Java ActorRuntime mains. Both
workflows explicitly checkout and assert the **exact pull-request head SHA**.
The commands are added but **NOT_EXECUTED** on the current source head until
a GitHub Actions job actually runs nonzero steps. This is not native
GraalVM, source-compiled `.ores`, or five-backend conformance.

### Important missing features

The added command worker serializes admissions on a dedicated host worker,
but has **not** proven cross-actor group COMMIT batching, scheduler
fairness, distributed leases, transactional outbox, power-loss behavior or
hard-real-time guarantees. Guest compiler/parser/borrow-checker integration
is absent; compiler must still enforce sealed state, deterministic reducers
and no suspended mutable loans. Snapshot migration and authenticated
distributed activation authority also remain open. Retain draft status and
fail closed for unknown provider outcomes.

Private-source transfer to a different GitHub organization remains
**not approved** by the source-owned security policy. No cross-org source
mirror, source-main mutation or merge is authorized by this draft.


### 2026-11-10 P0 snapshot-integrity follow-up

`SqliteCommandTransactionStore.verify` and
`SqliteCommandTransactionStore.loadVerified` now require a retained
checkpoint to match the original durable `ops` snapshot record **by
version, schema and exact payload**. A command index surviving journal
compaction is insufficient if the checkpoint BLOB is missing or altered.
The actual Java SQLite fixture now injects both modified snapshot bytes
and deletion of the original snapshot operation; both are expected
fail-closed recovery/duplicate-receipt tests. A strict source test PASS
has **not yet** been recorded.

Conformance [#668](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/668)
now independently verifies matching persisted snapshot and operation
checksums and legacy event payload hashes, including explicit
`UNVERIFIED_*` rejection for pre-upgrade rows without proof. Node and
Java do **not** share a physical database layout and are not being
claimed byte-for-byte database compatible.

A separately authored, **source-free** Node 22 SQLite runner
[run #42919657770](https://github.com/atto-lang/atto-compilers-parity-testing/actions/runs/42919657770)
successfully executed a 3-check SIGKILL/WAL canary and four independent
snapshot-integrity scenarios with no checkout or private source
transfer. Those results verify only synthetic SQLite behavior and
alternate-runner admission, **not** the exact Java source or compiled
AttoLang guest. Private source export remains disabled pending
independent destination-specific approval.


### 2026-11-10 adversarial follow-up: direct acknowledgments and recovery

An audit of the **legacy single-event** SQLite path found that a matching
operation ID/digest alone could previously return a durable duplicate even
if the corresponding `events` BLOB was deleted or changed. Its
`SqlitePersistenceStore.commit` duplicate path and
`verifyDurableCommit` now share a transaction-scoped
`committedPayloadPresent` check against the **actual** event BLOB/schema
or active snapshot bytes. A corrupted or compacted-away legacy event
must never be represented as a verified durable success. Legacy
`loadFenced` also now verifies each event/snapshot against its original
`ops` record rather than relying solely on contiguous versions and
lengths; new tests rewrite same-size payloads and assert fail-closed
restore.

`SqlitePersistenceStore.commit` also checks the shared
`command_commits` table when present and refuses a legacy event or
snapshot reusing a logical command ID. This is symmetric with the atomic
provider's prohibition on reusing old legacy IDs, including a test
that tries to reuse a durable logical deposit as a legacy event.

The **atomic command sidecar** no longer invokes arbitrary future
dependents before releasing admission capacity, and protects against
a synchronous future callback calling `close()` from its own writer
thread. A later external `close()` still joins the writer before
its SQLite Store may be disposed. Tests cover inline callback close,
second owner close and durable command retention.

`SqlitePersistenceStore.loadFenced` is now intentionally a strictly
legacy-only verified replay interface; logical multi-event command
journals must reactivate through the command-aware
`SqliteCommandTransactionStore.loadVerified` API. This behavior
is not a transparent migration of historical unverified databases.
The actual Java/JDBC and guest tests remain NOT_EXECUTED as long as
source-owned Actions jobs have no executed steps. Keep all related
PRs in DRAFT and preserve source-export restrictions.


### 2026-11-10 P0 bounded replay admission (additional hardening)

A successful durable write must not leave a journal that cannot be restored.
Both `SqlitePersistenceStore.commit` (legacy EVENT) and
`SqliteCommandTransactionStore.commit` (atomic multi-event) now enforce
the maximum **10,000 post-snapshot events** and **16 MiB of replay BLOBs**
BEFORE inserting new events. The `BEGIN IMMEDIATE` transaction serializes
these quota checks against writers sharing the same SQLite WAL database.

The new `Status.REPLAY_LIMIT_EXCEEDED` is a **definite, nondurable**
rejection rather than `OUTCOME_UNKNOWN`, because the check occurs before
the first INSERT. In particular, a full stream does not roll back unrelated
actors' successful writes in the legacy batch. Snapshot checkpoint writes
can advance the snapshot barrier, after which the bounded replay suffix
may resume. Stored historical operation IDs remain retained for dedup.

Neither writer trusts a checkpoint version merely because it appears in the
state row: the checkpoint must lie within the stream head, its payload and
schema must be valid and its original durable `ops` snapshot record must
match the bytes before it is allowed to reset replay accounting.

`SqliteReplayAdmissionTestMain` (included in exact-head strict Java 21
JDBC CI) now tests the 10,001st event, the 16 MiB boundary, both writer
APIs, same-batch isolation, snapshot barrier reset, verified
post-checkpoint recovery and altered checkpoint rejection. These tests
are **AUTHORED / NOT_EXECUTED** on the source repository because workflow
jobs continue to fail before the first step.

Independent locally executed Node 22 SQLite count/byte/checkpoint SQL
checks passed 7/7. A separately authored source-free runner
[run #42940311766](https://github.com/atto-lang/atto-compilers-parity-testing/actions/runs/42940311766)
executed 7/7 comparable checks alongside its existing 3 SIGKILL and 4
checkpoint-integrity canary assertions, with all seven Actions job steps
successful. Neither result proves Java/JDBC source compilation, exact-head
source SQLite tests or AttoLang guest code. No cross-org private-source
copy, release promotion or merge is authorized by this evidence.


### Subprocess crash/restart and exact-runner admission (2026-11-10)

`SqliteCommandCrashRestartTestMain` is a new **real JDBC** host
subprocess test, wired into the strict JDK 21 `javac -Xlint:all -Werror`
workflow and the same pinned SQLite JDBC classpath. Child processes
first exercise halt-before-commit and halt-immediately-after-provider-commit
without delivering any user-level ACK. Parent processes reopen the same
SQLite WAL database, raise the activation epoch, verify replayed events
and durable result, confirm exactly-once command identity, and reject
stale-epoch proof. The pre-COMMIT first-event SIGKILL phase is covered
by the **Node host** reference via a connection-local SQLite TEMP trigger;
it is not claimed as a Java JVM provider execution.

A source-owned Java JDBC workflow rerun
[#42948642624](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/42948642624),
attempt 2, again failed with no assigned runner and **zero executed job
steps**. No JDK compilation, JDBC integration, crash subprocess or
guest AttoLang test was executed in source CI. The failure is in the
execution-admission path but the specific billing/policy/root cause
is still unknown. Reuse the secure private-source export gate in
security draft PR #2694: do not mirror sources solely because a
different private organization's synthetic canary has working minutes.
