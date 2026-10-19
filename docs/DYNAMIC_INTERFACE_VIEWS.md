# Explicit `dyn` interface views (Java/Truffle initial slice)

**Status (October 2026):** A Java/Truffle source-level, checked nominal
interface view. This document is a staged contract, **not** a claim of
LLVM/Wasm/freestanding trait-object ABI parity. A runtime fat pointer,
`Box<dyn T>` allocation model, cross-generation vtable identity, and
`ActorRef<dyn Protocol>` are future steps.

## Motivation

`dyn Deployable` is neither `Any` nor unrestricted dynamic typing.
The compiler first validates nominal interface conformance (ordinary
`implements`), then allows a receiver to be called through an
explicitly erased interface view. A `dyn` marker survives both the source AST and static type checking as a
separate `Types.Dyn` nominal witness. It can be constructed only by a proven
class `implements` relationship or an already-checked compatible dyn witness.
`Unknown`, unbound generic values, and structural lookalikes cannot forge it.
There is no new allocation or native fat pointer in this stage.

```ores
define interface Deployable as
    fnc deploy(): int;
end
define class Database implements Deployable as
    pub deploy(): int { return 40; }
end
define class WebServer implements Deployable as
    pub deploy(): int { return 2; }
end

fnc deploy(dyn Deployable resource): int {
    return resource.deploy();
}
```

`dyn` may occur in parameter and return types and nested generic
type arguments (for example `List<dyn Deployable>`). This is
a nominal reference view, not an implicit heap-boxing operation.

## Acceptance criteria and current restrictions

1. The name after `dyn` must resolve to an ordinary nominal interface
   (or `trait`, which currently parses as an interface-like declaration).
   Primitives, classes, records, actor types, module contracts, unresolved
   names, and nested `dyn` are rejected.
2. The interface **and its inherited interfaces** must contain only
   object-safe methods: no field requirements, per-method generic
   parameters, or `self`-dependent argument/return types.
3. The concrete class must explicitly implement the nominal interface;
   structural shape similarity is insufficient.
4. `new dyn I()` and `spawn dyn I()` are rejected. No constructor,
   allocator, or actor capability is manufactured by erasure. Erased
   interface views inside ActorRef, ActorMail, ActorOutput, Channel and
   SharedMutex type arguments are rejected until safe transport exists,
   including when type aliases hide the erased view.
5. Existing class/interface method name-and-arity dispatch is preserved.
   The `dyn` marker does not change overload identity or permit
   first-class bound methods.
6. Borrow/move/actor rules remain those of the existing reference
   representation. This implementation does not claim new sendability,
   cross-actor ownership, allocation freedom, or hot-reload safety.
7. The compiler rejects `dyn Interface<T>` when its specialization contains
   unresolved `Unknown` or generic variables. This is a conservative initial
   restriction pending proof-carrying generic erasure.
8. Buffered channels reject direct and nested erased interface views, including
   ones found through concrete class fields and generic substitutions.

## Planned native and ownership contract (not implemented here)

For LLVM/Wasm, a future owned or borrowed `dyn` value must carry a
concrete object/region handle and an immutable, versioned, immutable
dispatch descriptor. The descriptor must have stable interface identity,
method-slot IDs keyed by name and caller-visible arity, typed ABI/effect
information, and generation membership. No raw vtable may outlive a
hot-reload generation; old objects require pinned code or explicit
verified migration before unloading.

* `&dyn I` / `rt borrow`: non-owning, lifetime-checked; no boxing.
* `Box<dyn I>`: explicitly allocated owner, move/drop semantics and
  allocator provenance; cannot appear until Box and native heap lowering
  are implemented and tested.
* Async dynamic calls: stable erased continuation ABI, cancellation
  ownership and effect checking before admission.
* `ActorRef<dyn Protocol>`: a message-only protocol view, never a
  direct method pointer into actor-owned state; requires a separately
  verified typed mailbox ABI and capability checks.
* Cross-isolate and untrusted boundaries must not transfer pointer/vtable
  addresses. They require explicit proxy/message serialization.

## Further required tests

* Java/Truffle: nominal success and non-conformance; inherited contracts,
  generic interface specialization, field/generic-method/self rejection;
  uncertainty and generic spoofing rejection; nested channel ownership;
  AOT-host execution; typed method dispatch.
* LLVM: fail-closed unsupported constructs first, then verified native
  AOT interface-object lowering, deterministic drops, and sanitizer
  tests. Never imply implementation parity from shared syntax.
* Hot reload: incompatible generation rejection, controlled migration,
  pinned code lifetimes and no use-after-unload.
* Ownership/security: no lifetime extension through dyn, no borrowed
  escape, no actor/private-state direct invocation, and capability-safe
  dispatch for untrusted actors.
