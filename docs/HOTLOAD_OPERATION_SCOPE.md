# Transactional hot-load operation boundary

This reconciles the import-free transactional loader from draft PR #2351
(branch tip b8d6e5083a4f402c5a1148e341e4dee1ab06fcbd) with current main.
Existing main CodeImageIdentity revocation and source-binding checks remain.

`reloadAndStart` validates and starts a candidate before epoch-checked serving
publication. `pinServing` selects and leases that committed route atomically.
`ServingOperation.call` invokes a public guest routine through the VM root
scheduler and awaits its async result. An independent physical operation pin
survives concurrent closure of the caller lease. Retirement and manager shutdown
reject while a physical call is active; callers retry retirement after drain.
Context cleanup is graceful, outside the manager monitor, inside a private
trusted embedding-entry scope. Guest code cannot issue embedding-thread permits.

Java exception: route publication, Truffle entry, VM root task scheduling,
context lifetime and CodeImageIdentity revocation are privileged host/runtime
primitives. Application state, mailbox protocols and request behavior remain in
`.atto` fixtures in the external hot-loading conformance suite. The public-root
control command reuses the existing invocation and scheduler implementations.

This protects managed calls that join all their actors/tasks before returning.
It is not automatic lifetime tracking for detached actors, raw `context()` users,
arbitrary retained polyglot Values, FFI callbacks, or untrusted isolate heaps.
Legacy `load`/`active` behavior is preserved and is not a production serving route.
Use `serving`/`pinServing` for transactionally published service generations.
There is no state-schema migration or ABI-compatibility admission claim.

Java and Native Image tests use the same `.atto` stateful actor application.
Native Image AOT builds the runtime/interpreter ahead of time; dynamically loaded
guest source is parsed/typechecked and interpreted without guest JIT. This is not
proof of closed-world machine-code AOT guest image replacement. LLVM, Wasm,
Forge and .NET parity remain unexecuted in this change.
