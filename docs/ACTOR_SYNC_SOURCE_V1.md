# Actor Sync source-progress v1 (candidate, not durable storage)

This PR adds [`stdlib/actor_sync.ores`](../stdlib/actor_sync.ores) as a **native Oreslang userland module**. It is a small, independently owned state-management building block that will be reused by the actual actor persistence/broker pipeline. It does **not** implement activation, SQLite/remote storage, network event delivery or an Oreslang FFI.

### CommitWindow

- Pins an incarnation number and one stable pending operation ID to an actor-local state instance.
- Rejects duplicate/inconsistent admissions and stale epochs.
- Keeps pending operations reserved when the I/O response is lost (`outcome_unknown`); a connection error is **not** proof of transaction rollback.
- Only an already **verified by the trusted adapter** ACK can advance the durable cursor. This class is only metadata; `acknowledge` is **not** itself an authenticated receipt verification.
- `abort_known_failure` is illegal after the outcome becomes unknown; `reconcile_not_committed` requires a separate authoritative idempotency lookup before the caller may invoke it.
- Fail-closed bounded signed 32-bit version arithmetic; revision 2147483647 is reserved as invalid. This module's revision domain is narrower than the native SQLite reference's 64-bit domain. The eventual cross-compiler ABI must reconcile it before guest durable state commits can claim parity.

### ReplicaCursor

- An explicit non-durable **reader watermark**, separate from the writer's committed version.
- Accepts only sequential deltas and non-regressing owner epochs.
- Gaps poison incremental progress. A trusted complete snapshot may skip versions, but cannot rewind or overwrite the same epoch/revision.
- The caller must commit state bytes and watermark together in an actor-owned turn; this module only checks cursor metadata and cannot prevent malicious host events.

### Canonical source and executable fixtures

These two files have **identical Git blobs** in [platform-conformance PR #300](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/300):

| File | Git blob SHA-1 |
|---|---|
| `stdlib/actor_sync.ores` | `3aae079ffe3be8bc2eb2b77bacbd6bddf7e1ce65` |
| `src/test/resources/actor-sync-v1/cursor-progression.ores` | `a454e7107ab62bbaa38cca44627c794b3bf0d4c2` |

`ActorSyncStdlibTest` uses `LinkedProgramRunner` to compile/interpret real Oreslang source and assert exact outputs. GitHub workflow `actor-sync-source-guest.yml` pins those Git blobs, head/tree, built JAR SHA-256, and runs `mvn -Dtest=ActorSyncStdlibTest test`.

**As of authoring, that workflow has no executed-job-step receipt.** No Java guest PASS may be recorded based only on this authored test. This is not a .NET NativeAOT, LLVM, Wasm, Cranelift, distributed SQLite or network-subscription conformance implementation.

### Next

The first true actor-sync guest acceptance requires `define isolated actor Cart as ... end`, state + critical handle_state, asynchronous storage effect without holding a carrier, commit-before-reply, restart/recovery, replication between two actual actors/processes, stale writer rejection, and exact source guest oracles across five compilers. Do not implement these with Java-only class semantics or blocking SQLite calls inside the guest actor turn.

## 2026-11-03 higher-generation checkpoint hardening

An announced actor epoch strictly higher than the replica's stored epoch poisons incremental progress and records the **highest observed epoch**. Additional announcements can only raise that watermark. A full snapshot whose epoch is lower than the recorded maximum cannot clear the fence, even if its state revision or payload matches. After a separately authenticated snapshot meeting the watermark, `apply_snapshot` clears the poison; the same exact epoch/revision is accepted only when clearing an existing poison. This is metadata policy, not storage authority or an authenticated snapshot implementation.

The same source is pinned to platform conformance as Git blob `3aae079ffe3be8bc2eb2b77bacbd6bddf7e1ce65`. A third shared fixture `highest-epoch-fence.ores` is Git blob `ae9063472b39f554fa195d79283bd9952e57483d`. Both the Java guest fixture and the C++ host epoch fence still lack stepful GitHub Actions receipts. The target public language is **AttoLang**, using `.atto` source files; the `.ores` files in this transitional Java compiler are compatibility fixtures, not proof of `.atto` admission.


## November 3 package admission and .atto execution candidate

The first source-guest CI attempt could not compile the new module as written: `CoreLibrarySources.STD_SOURCES` did not list actor synchronization. The branch now registers **`actor_sync.atto` as the single canonical compiler-owned hosted standard-library unit**, packages `.atto` files alongside retained `.ores` files, and treats `std/actor_sync`, `std/actor_sync.atto`, and `ores.std/actor_sync.ores` as the **same checked module identity**. The compatibility `stdlib/actor_sync.ores` is byte-identical to `stdlib/actor_sync.atto`, not a separate runtime instance. The canonical module Git blob remains `3aae079ffe3be8bc2eb2b77bacbd6bddf7e1ce65`.

The Java mixed-language source front end admits `.atto` as the native AttoLang source extension, and project-root import resolution discovers `.atto` modules. An ambiguous suffixless import with both `name.atto` and `name.ores` **fails closed**; callers must specify the extension during migration. Existing `.ores` paths continue to work. The updated Java test suite includes canonical packaged-module verification, a real `.atto` entry executing the shared cursor fixture, and an `.atto` isolated-actor turn using the source commit-window metadata.

**Evidence gate:** these are source-authored tests, not demonstrated guest passes. Run `ActorSyncStdlibTest`, `OresProjectConfigTest`, and `NativeCoreLibraryTest` on the exact checked-out PR tree. No `suspends` storage adapter, persistent Oreslang guest state, or verified two-process AttoLang guest recovery exists yet.
