# Untrusted actors

`untrusted actor` is Oreslang's first-class execution domain for hostile or
hot-loaded code. It is not merely a private actor with a convention attached.

## Syntax

```ores
untrusted actor Worker {
  pub fnc run() => void {
    for (let i = 0; i < 1_000_000; i = i + 1) {
      work(i);
    }
  }
}
```

`untrusted actor fnc` is also reserved for one-shot actor entrypoints.

## Security contract

The runtime always replaces ambient authority with the fixed
`IsolatePolicy.untrustedActor()` ceiling. The default guest has no ambient
capabilities: no filesystem, network, IPC, GPU, FFI/native access, reflection,
environment access, child processes, threads, shared memory, hot loading, or
actor spawning.

Untrusted mailboxes accept data values only. Live `ActorRef`, shared-memory,
and mutex capabilities cannot be smuggled into the actor. Untrusted actor code
cannot send actor messages or stop sibling actors. Self-stop remains legal so a
guest can terminate itself cooperatively.

The trusted supervisor owns code loading and routing. `HotReloadManager`
therefore has separate supervisor and guest policies; the supervisor may hold
`HOT_CODE_LOAD` while the guest does not.

## CPU scheduling and loops

Source code does not need to write `yield`. Oreslang already inserts scheduler
safepoints on every `for` and `for-of` iteration. For untrusted execution,
those safepoints additionally:

- charge a deterministic fuel budget;
- enforce the per-turn wall-clock deadline;
- enforce per-thread CPU time when the JVM exposes it;
- observe cancellation/interruption; and
- yield to the dispatcher at a bounded quantum.

Callable entry also performs a safepoint for untrusted actors, so direct and
mutual recursion cannot bypass loop accounting. The compiler/runtime invariant
is that cyclic control flow must cross an untrusted safepoint; banning one loop
syntax is not a security boundary.

## Compile-time admission

Capability admission re-checks an untrusted actor body under the fixed
untrusted policy even when the surrounding module/context is more privileged.
Local helper functions are checked transitively. Imported calls are rejected
for untrusted actors until imports carry explicit effect metadata, avoiding a
capability laundering path through unknown code.

These compile-time checks are defense in depth. Runtime capability checks and
the Graal `SandboxPolicy.UNTRUSTED` boundary remain authoritative if malformed
or stale compiled input bypasses the normal compiler.
