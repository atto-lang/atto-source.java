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

## Static proof obligation (immutable and mutable instance fields)

For each explicit constructor independently, track two sets across normal
control flow:

- `must`: all declaration-uninitialized fields assigned on every incoming path;
- `may`: all declaration-uninitialized fields assigned on any incoming path.

For a blank immutable `val` field, an assignment `self.field = expr` is
admitted only if the field is absent from `may`, and then added to both sets.
For a blank mutable `let` field, first assignment is required on every normal
path, but subsequent assignments are permitted. Both kinds of assignment
establish `must` and `may` for that field. At branch joins, `must` is the **intersection**
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

Reads of any blank `self.field` before definite initialization are rejected.
Only a direct constructor statement establishes a first assignment; writes
hidden inside expression forms, lambdas, or callbacks cannot establish the
proof. Constructor `self` remains a construction-scoped, unpublished receiver
that may not escape into borrowed, retained or asynchronous callbacks.

This first Java slice intentionally fails closed for loops, `try/catch/finally`,
`match`, `select`, and `defer` while any required instance field remains
uninitialized. These constructs may appear after initialization, but cannot
write constructor-initialized immutable `val` slots. Mutable fields retain their
normal reassignment permission. Extending the checker to them requires sound normal/exceptional CFG,
suspension and deferred-effect handling—not a syntactic count of assignments.

The interpreter separately rejects a second immutable-field write and refuses
to return an instance with any uninitialized field. The compiler now mirrors
that all-fields initialization requirement for explicit constructors. A constructor may initialize only fields declared by its own class;
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
