# P0(DB) stage 4 — live ActorRuntime-minted WAL actor proof

**Status: draft source integration; NOT a compiler-registered Oreslang storage builtin, not an authenticated tenant storage registry, and NOT a proven durable database.** Stacks on Java/Graal PR #2373 and tracks Java issue #2358 and DB issue #4.

## Narrow source change

- `ActorRuntime.StorageActorProof` is package-private and has a private constructor; `ActorRuntime.mintStorageActorProof()` can mint it only on a real, admitted physical actor turn. It consults both scheduler thread-local actor execution and the **same physical ActorCell** in the owning `ActorRuntime`, and under `lifecycleLock` verifies the single active turn, correct physical carrier, scheduler admission, live ref, non-UNTRUSTED actor, and lack of STOP/force-kill/finalization.
- The proof retains the exact `ActorRef` object, stable actor execution-domain identity and runtime. It checks live incarnation again after an `await`/carrier migration, rather than trusting the caller's actor ID, generation or Java thread.
- `WalStorageActorGate.captureOnActorTurn` now rejects a caller-supplied different `ActorRef` even when its ID/generation values match. It still requires the current `OresContext` runtime and scoped WRITE permission. The actor gate uses the runtime-minted proof for append/receipt verification, with physical `force(true)` and single-use receipt consumption inherited from PRs #2364 / #2373.
- `src/test/java/dev/oreslang/runtime/WalStorageActorProofRuntimeTest.java` exercises actual `ActorRuntime.spawnShared` mailbox callbacks: mint inside an admitted turn, reject off-turn, reuse across a later turn on the same incarnation, and deny after STOP. This is **not** a compiler guest test. It must run in the complete Maven/Truffle build.
- Host-only shim regression includes a **same-ID, same-generation distinct-ActorRef forgery** negative test; isolated strict Java21 compilation and 28/28 assertions were run locally five times. The shim cannot establish scheduler integration; it merely protects the negative regression in absence of Graal dependencies.

## Required gates, and what does NOT follow

1. Execute full `mvn -B -ntp -Dtest=WalStorageActorProofRuntimeTest test` with independently pinned Java, Maven, dependencies, source commit+tree, and actual nonzero CI steps. This **was not executed** in the local host-only environment (Maven/Graal artifacts unavailable), so no real-runtime pass is claimed.
2. Prohibit guest reflection/interop access to `ActorRuntime.mintStorageActorProof`, `StorageActorProof`, `WalStorageActorGate` and `WalStorageFileOwner`. Package privacy alone is **not** a sandbox. The proof is not a runtime-issued tenant grant and cannot be transferred as actor DATA.
3. Authenticate tenant, isolate, shard, actor UUID and numeric scope in a supervisor-owned registry; the host still passes `Scope` and a policy root. Scope's numeric `actor` field is **not** derived from the real actor UUID in this patch and MUST NOT be treated as an identity mapping.
4. Acquire an exclusive regular-file descriptor through a race-resistant directory-relative mechanism and maintain independent physical ownership/retirement pins. This patch **does not** provide `openat2`, an OS sandbox, directory fsync, or actor-STOP automatic native resource retirement.
5. Register a typed async, non-DATA-sendable `.ores` storage builtin; connect `DbWalActor`, replay/idempotency/index and verified sync-before-ACK semantics. Guest, crash/restart and LLVM differential evidence are separately required.

Do not merge until complete real runtime/guest gates execute and independent review/branch protection are present. `steps=[]` means runner admission failure, not a compiler result. No private cross-org transfer was performed.

## Stage 5 — runtime-local scheduler slot binding

ActorCell mints a positive monotonically increasing `storageActorOrdinal` in the owning
ActorRuntime, and the runtime-minted StorageActorProof carries that exact ordinal.
WAL capture rejects any claimed `Scope.actor` not equal to the scheduler-minted slot.
The slot is **NOT a persisted actor UUID**, tenant/isolate grant, or recovery identity.
A new runtime must authorize a new generation and reconcile existing WAL through
trusted recovery before writes can resume.

Local isolated Java 21 `javac -Xlint:all -Werror` host-shim: 29/29 checks, five
repeats. The new negative regression rejects an otherwise matching capture
with a foreign numeric actor slot; the real-runtime JUnit now checks positive,
stable slots across turns but remains **NOT_EXECUTED** without Maven/Truffle.
This source change does NOT establish confined FDs, exclusive leases, supervisor
tenant mapping, guest native builtins, or crash durability. Keep draft.
