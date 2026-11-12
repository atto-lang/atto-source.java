# Optional receiver bindings on namespace actor methods (v1)

Status: parser/static actor-namespace binder implementation candidate. The
separate manifest-linked **stateless** actor guest remains the only executable
namespace-actor subset on this branch. Sealed attached state and implicit
ordinary method-call lookup are not thereby made executable.

## Canonical declaration

```atto
namespace workers;
declare actor Worker implements Actor;

define state on actor Worker as {
    let int v = 5;
}

pub mtd (Worker) big_ly(): void {
    process();
}

pub mtd (Worker _) process(): void {
    handle_state(|s| -> { s.v = 6; });
}
```

- `(Worker)` is the preferred spelling. The compiler inserts the current
  actor's lexical `self` receiver; it is not a caller-visible argument.
- `(Worker _)` explicitly discards the source-level receiver binding;
  `_` is never made available as a runtime variable. It normalizes to the
  same compiler-owned `self` receiver.
- `(Worker self)` is equivalent to both. `self` is a reserved token and
  must be accepted by the parser, not mistaken for an ordinary IDENT.
- `(Worker worker)` preserves the named source alias, but cannot change
  nominal actor identity, its namespace, borrow permissions, or method ABI.
- This rule applies to receiver-first and legacy name-first `mtd` syntax;
  it does not change the meaning of normal typed function parameters.
- A parameter cannot shadow the hidden `self` or a named receiver alias.
  Duplicate `name/arity` slots remain duplicates regardless of spelling.

## Call resolution and security

Inside a checked instance method, `process()` should select the same typed
lexical receiver slot as `self.process()`; no global namespace search,
actor-method value capture, implicit remote message, actor spawn or new
scheduler task may be introduced. The ordinary implicit method lookup change
must be integrated separately and retain all visibility, overload, effects,
ownership and turn checks.

`handle_state(callback)` must eventually be exactly the compiler-owned
intrinsic `self.handle_state(callback)`, not an ordinary user-overridable
method. Mutable state requires a verified actor turn, critical-write loan,
non-escaping callback, no suspension while borrowed and generation fencing.
Neither receiver omission nor the `_` spelling grants those capabilities.

The attached-state example above is a *language acceptance target*. This
branch does not claim executable `handle_state` for declared actors, or
production support for the fully inferred `|borrow mut s|` callback syntax.

## Verification boundaries

Static tests cover parse acceptance, canonical receiver names, hidden
receiver arity, cross-file attachment, duplicate slots, malformed heads,
parameter-shadowing and namespace isolation.

A separate JVM/Graal real-guest regression rewrites the stateless actor's
ordinary method and lifecycle method receivers to omitted/`_` forms and
checks the same mailbox output. It must execute on the exact PR head before
this behavior is certified. LLVM, Wasm, Forge, .NET and other backends remain
NOT VERIFIED for this new source spelling until their own compiler-native
positive/negative guest cases execute. A zero-step Actions run is not
execution evidence.
