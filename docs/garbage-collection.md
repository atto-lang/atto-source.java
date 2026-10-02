# Garbage collection and lifetimes

Oreslang treats garbage collection as a fallback runtime service, not as the
primary memory-safety model.

## Ownership first

Ordinary Oreslang values are governed by the ownership checker:

- immutable primitives are Copy;
- class/list/object/function values are move-only by default;
- `&T` is a shared borrow;
- `&mut T` is an exclusive borrow;
- borrows end when their lexical scope closes;
- a borrow of a local value cannot be returned;
- borrowed values cannot escape through closures.

This gives Oreslang Rust-like deterministic lifetime safety without requiring
explicit lifetime parameters in ordinary code. Explicit lifetime syntax should
only be added when inter-procedural cases cannot be inferred safely.

The runtime collector MUST NOT be used to make an otherwise-invalid ownership
program valid.

## What the runtime collector is for

The VM also owns objects that are outside the normal guest ownership graph:
reflection adapters, FFI wrappers, host handles, interop resources, compiler
metadata, and similar runtime artifacts. Those resources can register a cleanup
hook with `RuntimeGarbageCollector.track(owner, cleanup)`.

The collector weakly references the owner, so tracking cannot extend a guest
object's lifetime. A cleanup hook is eligible only after the owner is no longer
reachable.

A lightweight VM sweep runs periodically. Periodic sweeps do not call
`System.gc()`; the JVM remains free to use its adaptive heap collector.

## Explicit collection

`process.gc()` performs process/context-wide runtime cleanup and then makes a
best-effort JVM GC request. It returns a report map. The call requires the
`GC_CONTROL` capability and is denied by the strict FaaS policy.

```ores
pub fnc main() => void {
  val report = process.gc();
  stdio.println(report);
  return;
}
```

`actor.gc()` performs only actor-domain cleanup. It does not request global JVM
GC, so one actor cannot create a global stop-the-world pressure point simply by
calling its local GC API.

```ores
actor.gc();
```

Calling `actor.gc()` outside an actor mailbox turn is an error.

## Isolation rule

Tracked runtime resources inherit the current semantic actor domain at
registration time. Actor domains are keyed by runtime + actor identity, not JVM
carrier thread identity. This remains correct if actor turns move between
threads in a future pooled scheduler.

## Shutdown

Closing an Oreslang context closes its actor runtime first and then drains all
remaining registered host cleanup hooks. Shutdown cleanup is best-effort; it is
not a substitute for explicit close/release APIs for externally durable
resources.
