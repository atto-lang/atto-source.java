# Batch persistence sidecar — Java host integration slice

**Status: host adapter implementation, NOT a compiled .ores PersistenceSidecar actor and NOT production durable storage.** Reference design: https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/227

- BatchPersistenceSidecar owns a bounded tenant queue, separate storage worker, per-actor/slot write metadata, scheduled or manual flush and CAS/fencing provider contracts.
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
