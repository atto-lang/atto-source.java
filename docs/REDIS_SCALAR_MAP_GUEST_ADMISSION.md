# Redis scalar Map guest: staged compiler/runtime contract

Status: DRAFT, stacked on the Atto canonical guest branch. This implements a **narrow** source-level `Map<string,int>.set(key,value)` and `delete(key)` surface in the Java/GraalVM type checker and Truffle guest evaluator.

- `TypeChecker.builtinCollectionMember` resolves the two witnessed, nominal scalar arguments and Bool deletion result. Existing `Map` literal and typed `Map<K,V>` constructor checks remain in force; keys must be String/Symbol and values from the closed scalar domain. No arbitrary object graph, FFI or Java Map is exposed.
- `OresEvalRootNode` delegates `set` to the existing synchronized, quota-checking `OresKeyedMap.put` implementation and `delete` to atomic `OresKeyedMap.take`. `size` remains read-only.
- The .atto fixture from `atto-redis-clone.atto` Git blob `5637be64ab7e5bfebcdef8db2f91d8ebfd67fac3` executes SET, indexed GET, DELETE, verifies deletion returned true and map size zero, then returns 42.
- Scoped private premerge CI: https://github.com/voxletra/voxletra-e2e/actions/runs/43037860295. Compiler baseline exact Git SHA `053b76b96ab65e560c21efbaa609fc8584e27868`, with two documented compiler file changes. Passed actual Java/GraalVM Truffle guest. This is *not* a full source tree or merge-tip certification.

**NOT implemented:** actor-owned `HashMap<Bytes,KvEntry>` in .atto, serial command turn semantics, WAL fsync, SST and manifest, checkpoint, TCP RESP server in .atto, cross-backend parity, Native Image AOT, Rust/Atto comparable throughput. Do not merge based on this narrow guest receipt alone without security/type/ownership review and correct base dependency resolution.
