# Stage 2: runtime actor-incarnation WAL gate (Java/Graal source)

Status: **draft source-level runtime seam**, stacked on Java WAL physical-owner PR #2364. This is a permission/identity hardening slice; it does not register an Oreslang built-in, produce a durable DB ACK, or prove that the actual GraalVM actor scheduler compiled this class. Tracks #2358 and Oreslang DB PR #11.

## Changes

- `WalStorageActorGate.captureOnActorTurn` requires a *currently executing* actor, its actual `ActorRuntime`, current `ActorId`, stable `currentActorExecutionDomain()` reference, actor generation matching the host-provided `WalStorageFileOwner.Scope`, a live actor ref, non-UNTRUSTED kind, and an effective scoped FILESYSTEM_WRITE permission checked by `OresContext.requirePermission`.
- `attachOffCarrier` rejects actor carriers and stale/retired actors, then adopts an **already opened** FD through `WalStorageFileOwner.openVerified`. The runtime must have independently proved exclusive tenant/shard inode custody, safe directory-relative opening, and parent-directory synchronization. This source **cannot prove** custody and provides **no guest file/path/FD API**.
- `appendAndSyncOnActorTurn` calls the existing bounded physical file owner and binds its returned `CompletionStage` to an immutable pending operation and a fresh SHA-256 copy of submitted bytes. No physical I/O runs on the actor carrier.
- `verifyPhysicalSyncOnActorTurn` requires reentry on the same live actor incarnation after await, checks that the exact completed receipt OBJECT belongs to that pending stage, validates owner scope, op ID, sequence and SHA-256. This prevents accepting a genuine receipt swapped from another pending write. A per-pending atomic one-use verification fence rejects duplicate acceptance of the same physical receipt. This is not a cross-restart idempotency ledger and it returns `void`: **no `Committed` guest value, no index mutation, no replay ledger**.
- `revokeAndCloseAsync` marks the capture revoked and delegates quiescence to the owner; operations with uncertain physical outcome remain unknown, never implicitly rolled back.

## Local checks and proof classes

- New isolated `javac -Xlint:all -Werror` build compiled `WalStorageActorGate` plus actual `WalStorageFileOwner` with narrow **test-only ActorRuntime/OresContext API shims**; 27/27 host checks repeated 5 times, including real FileChannel append/force and reopen, wrong runtime/actor/domain, UNTRUSTED, stale actor/permission, receipt swap, mutation-after-submit and revocation. The test stubs intentionally model actor API shape; they **do not run the actual ActorRuntime**.
- `scripts/verify-wal-storage-actor-gate.sh` chains the previous exact-head host gate, checks real Java ActorRuntime/OresContext source signatures, then executes the 27-case shim suite with a byte-exact oracle. A signature grep is **not** a full Maven/Graal build.
- Local host tests and source-shape assertions must not be cited as compiler-backed `.ores` guest, isolate admission, or durability evidence.

## P0 still absent; reject guest exposure

1. Actual runtime-issued tenant/shard grant registry and confined FD opening, including symlink/hardlink/mount controls, regular-file inode identity, exclusive lease, parent-directory sync. `Scope` and policy root passed from a host caller are **not authenticated** solely by this class.
2. Full compiler/Maven build against `ActorRuntime`, `OresContext`, Truffle and actual typed AOT runtime registration. Do not infer compatibility from the shim compile.
3. Non-DATA-sendable storage capability; actor-incarnation retirement/physical IO loan pin that automatically revokes and quarantines the owner during STOP; no guest reflection/host Java interop path to this class.
4. `.ores` `DbWalActor` true async append/sync/await, critical-loan release before suspension, verified sync then index publication then `Committed`; durable op ID ledger and recovery.
5. Independently pinned, **nonzero-step** exact-HEAD CI and actual guest positive/negative, process restart/crash and filesystem fault tests; independent review and protected main before any merge.

No private cross-org mirror, no repository merge, no unapproved compiler API exposure.
