# Batch persistence sidecar — Java host integration slice

**Status: host adapter implementation, NOT a compiled .ores PersistenceSidecar actor and NOT production durable storage.** Reference design: https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/227

- BatchPersistenceSidecar owns a bounded tenant queue, separate storage worker, per-actor/slot write metadata, scheduled or manual flush and CAS/fencing provider contracts.
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
