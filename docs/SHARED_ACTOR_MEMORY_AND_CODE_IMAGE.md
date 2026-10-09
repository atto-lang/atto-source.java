# Guest single-flight, parallel shared actors, and read-only code images

## Runtime invariants

- ROOT and each daddy/supervisor own separate serial guest-turn lanes.
  Nested synchronous function calls stay in the *same* turn.
- Different SHARED actors **can** run simultaneously on separate threads of
  the shared actor dispatcher. Every actor retains its own mailbox execution
  lease and mutable heap/state ownership.
- Cross-actor writable cells must be explicit capabilities with synchronized
  or atomic access, immutable/SharedSafe values, or mailbox-owned proxies.
  The process-local object graph being in one JVM address space **does not**
  magically make arbitrary writes thread-safe.
- PRIVATE/UNTRUSTED isolation still needs explicit copy/ownership, capability
  gates and where supported physical memory bounds, not just OS-thread policy.

## Shared code vs shared memory

\`SharedCodeImageStore\` is the existing per-OresContext store. It shares the
exact same immutable \`Ast.Program\` object and \`CodeImage\` across all actor
kinds in that context. The integration tests check reference identity and
that sibling SHARED actors can run concurrently without duplicating the
program AST.

The store is **not** currently a multi-OS-process native text cache or a
guarantee of shared JIT machine instructions across separate Truffle Engines.
JIT, AOT, and hot-reload generations must respect immutable image identity,
generation provenance, and explicit retirement. Future code cache deduplication
must never share writable closure/global/actor data.

## Runtime-only timer scope

\`OresVM.scheduleTimer(owner, delay, callback)\` owns a one-shot registration
inside \`ProcessTimerService\`, whose single clock thread only completes
\`OresFuture\` and wakes the process lane. It must never run callback code.
\`finishRootMain()\` closes the ROOT timer scope, rejecting new timers and
cancelling pending callbacks. Independent daddy scopes survive ROOT main exit,
and VM shutdown cancels all remaining scopes. These are runtime APIs, **not**
new Oreslang \`timer 55s\` syntax or a complete \`std.process.exit\` implementation.

## Fail-closed compatibility boundary

The legacy \`AsyncRuntime\` virtual-thread generator producer blocks its
Java stack on a yield handshake and cannot safely enter the ROOT lane.
\`OresContext.executeAsyncTurn\` therefore rejects legacy guest producers
rather than bypassing process admission in trusted contexts. This is a
deliberate temporary regression of source async/sync generator execution;
the corrective fix is continuation-lowering of generator state, not a global
lock, direct producer callbacks, or a virtual-thread exception to the model.
Standalone host \`AsyncRuntime\` tests can still use the runtime bridge.

## Proof status

\`ProcessTurnTrace\` records actual per-scheduler admission/await/cooperate/
completion/exit transitions. Its verifier rejects two ENTERs without EXIT in
a single-flight lane, mismatched turn boundaries, and truncated traces.
\`formal/process-turns\` checks abstract invariants; integration tests
compare real scheduler traces to the **safety projection** of that model.
This is not yet a complete simulation/refinement proof of channels, mailbox,
generators, timer lifecycle and all guest entrypoints.

Before claiming 100% coverage, add per-process state ownership/capability
typing, generator lowering, source-timer syntax/typed await, and hot-reload
generation tracing. Keep PR draft until CI executes and conformance passes.
