# Oreslang: blank `val` instance fields (Java compiler slice)

## Canonical declaration

```ores
define class Counter as
    val int count;

    constructor() {
        self.count = 0;
        return;
    }

    constructor(int n) {
        self.count = n;
        return;
    }
end
```

A `val` instance field with no declaration initializer is a **constructor-initialized
immutable slot**, not `lateinit` and not a runtime-settable property. No new keyword
is required. `val T x: expression` remains initialized before the constructor,
and cannot be overwritten by it. `let` permits later mutation; `const` needs a
compile-time initializer. `final` is an accepted compatibility spelling for the
same fixed slot kind as `val`.

## Static proof obligation

For each explicit constructor independently, track two sets across normal
control flow:

- `must`: blank val fields initialized on every incoming path;
- `may`: blank val fields initialized on any incoming path.

An assignment `self.field = expr` is admitted only if the field is absent from
`may`, and then added to both sets. At branch joins, `must` is the **intersection**
and `may` the **union** of normally continuing branches. Returning branches
require complete `must` state *before* the return and do not contribute to
later joins. An implicit fallthrough also requires complete `must` state.

Examples:

```ores
// Allowed: mutually exclusive initialization.
if n > 0 then
    self.count = n;
else
    self.count = 0;
fi

// Rejected: one path leaves count blank.
if n > 0 then
    self.count = n;
fi

// Rejected: second write is reachable on one path.
if n > 0 then
    self.count = n;
fi
self.count = 0;
```

Reads of a blank `self.field` before definite initialization are rejected.
Only a direct constructor statement can initialize a blank val slot; writes
hidden inside expression forms, lambdas, or callbacks cannot establish the
proof. Constructor `self` remains a construction-scoped, unpublished receiver
that may not escape into borrowed, retained or asynchronous callbacks.

The Java checker now uses the shared assignment-count interval IR. `if/else`,
`switch`, and statement `match` participate in constructor dataflow. For
`match`, this local pass proves exhaustiveness only from an explicit unguarded
catch-all (`_` or a binding pattern); the general match checker may prove
richer finite-domain exhaustiveness separately.

Repeating loops, `try/catch/finally`, `select`, and `defer` still fail
closed while a blank val slot remains incomplete. Extending them requires
explicit backedge, exceptional, suspension and deferred-cleanup edges—not a
syntactic count of assignments.

Construction suspension is a separate invariant: `await`, asynchronous
iteration, blocking channel operations and blocking selects are rejected from
constructors so construction-scoped `self` cannot cross a scheduler boundary.

The interpreter separately rejects a second immutable-field write and refuses
to return an instance with any uninitialized field, including ordinary mutable
fields. A constructor may initialize only fields declared by its own class;
initialization of inherited blank fields awaits explicit super-constructor
chaining. No runtime field-default fabrication is permitted.

## No explicit constructors

Until synthesized constructors are replaced with an explicit migration policy,
classes **without any explicit constructor** preserve their current legacy
positional-field behavior. The new proof obligation applies only to classes
that declare constructors.

## Backend and evidence boundary

This change is a Java/Graal frontend/runtime implementation. LLVM, .NET,
Wasm and Forge must not claim parity until they run the same positive and
negative source fixtures and prove exact-once writes, early-return checks,
ownership isolation, and runtime storage behavior. Source-org Actions failing
without executable steps do not count as a test result.


## Semantic IR

The portable constructor/callable/recursive-type model is specified in
[`CONSTRUCTOR_SEMANTIC_IR.md`](CONSTRUCTOR_SEMANTIC_IR.md). Backends may use
different physical receiver or closure representations, but overload identity,
assignment-count joins, construction-region publication rules and recursive
type identities must match that contract.
