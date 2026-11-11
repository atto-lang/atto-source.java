# Redis scalar Map guest: staged compiler/runtime contract

Status: DRAFT, stacked on the Atto canonical guest branch. This implements a **narrow** source-level `Map<string,int>.set(key,value)` and `delete(key)` surface in the Java/GraalVM type checker and Truffle guest evaluator.

- `TypeChecker.builtinCollectionMember` resolves the two witnessed, nominal scalar arguments and Bool deletion result. Existing `Map` literal and typed `Map<K,V>` constructor checks remain in force; keys must be String/Symbol and values from the closed scalar domain. No arbitrary object graph, FFI or Java Map is exposed.
- `OresEvalRootNode` delegates `set` to the existing synchronized, quota-checking `OresKeyedMap.put` implementation and `delete` to atomic `OresKeyedMap.take`. `size` remains read-only.
- The .atto fixture from `atto-redis-clone.atto` Git blob `5637be64ab7e5bfebcdef8db2f91d8ebfd67fac3` executes SET, indexed GET, DELETE, verifies deletion returned true and map size zero, then returns 42.
- Scoped private premerge CI: https://github.com/voxletra/voxletra-e2e/actions/runs/43037860295. Compiler baseline exact Git SHA `053b76b96ab65e560c21efbaa609fc8584e27868`, with two documented compiler file changes. Passed actual Java/GraalVM Truffle guest. This is *not* a full source tree or merge-tip certification.

**NOT implemented:** actor-owned `HashMap<Bytes,KvEntry>` in .atto, serial command turn semantics, WAL fsync, SST and manifest, checkpoint, TCP RESP server in .atto, cross-backend parity, Native Image AOT, Rust/Atto comparable throughput. Do not merge based on this narrow guest receipt alone without security/type/ownership review and correct base dependency resolution.

## Ownership hardening v2 (2026-11-11)

Adversarial **real Atto compiler + Truffle guest** probes exposed 3 fail-open cases in the first Java patch:
1. `const f = db.set` and `const f = db.delete` compiled and executed as escaped mutable Map closures.
2. A function parameter of impossible `Map<int,int>` domain could call `set`, bypassing the constructor-only closed scalar domain gate.
3. `const Map<string,int> db` could call `set`, despite its immutable binding.

This patch now applies one closed domain gate for direct reads and members, forbids first-class map mutator values, and accepts direct `set`/`delete` only on a writable `let` local or a writable actor `self` field. A class named `Map` cannot reuse the built-in typed-map surface. No mutable Map operation may masquerade as a borrowed/temporary receiver or a const/val receiver. OresKeyedMap remains atomic and quota validating at the runtime boundary.

New `AttoRedisMutationAuditProbeTest` is an **asserting regression suite** (not merely a printed probe). It checks escaping set/delete, unproven key/value domains, immutable owner writes, use-after-`rt take`, wrong delete key, missing delete and overwritten value. Existing `AttoRedisScalarMapMutationGuestTest`, typed-empty and nonempty-map suites still execute.

Executed exact private carrier run: https://github.com/voxletra/voxletra-e2e/actions/runs/43041735134
- 25/25 Java/GraalVM JUnit tests passed; no failed/skipped/error tests.
- Verified baseline compiler snapshot 053b76b96ab65e560c21efbaa609fc8584e27868 with 2 altered compiler blobs.
- Hardened TypeChecker Git blob 672360990f0d57ac0cbbb847f47087d5e69625e4; unchanged OresEvalRootNode blob e5b1ae4378a3d96772cc2909f86f29b342cd5ff3.
- Exact audit JUnit blob dde631d28c0d7ac5978b4711e70006aedbe643e7.

**Scope limitations:** lexically writable `let` is not a complete proof of linear ownership or freedom from all aliases; source-level ownership and actor transport still need broader independent audits. `KvShardSmoke` continues to use legacy direct actor fields, *not* sealed `handle_state`. None of this builds the Atto TCP listener, WAL, SST, manifest or checkpoint. No Atto/Rust throughput ratio exists. Keep draft until linked full Redis guest integration, security review and exact-tree verification.
