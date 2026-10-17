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

For each explicit constructor independently, every blank `val` field carries a
saturated assignment-count interval `[min,max]`, where `2` means two or more
writes. The important states are `[0,0]` (uninitialized), `[0,1]` (maybe
initialized), and `[1,1]` (definitely initialized exactly once).

A direct assignment `self.field = expr` is admitted only from `[0,0]`.
The right-hand side is checked before the write commits. At a control-flow join:

```text
join([a,b], [c,d]) = [min(a,c), max(b,d)]
```

A normally returning constructor edge, including implicit fallthrough, requires
`[1,1]` for every blank `val`. Returning branches do not contribute to later
joins. This interval form is equivalent to MUST-intersection/MAY-union for the
current proof while retaining explicit assignment cardinality for later loop and
callback analysis.

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
proof.

Constructor `self` remains a construction-scoped, unpublished receiver. An
ordinary callback cannot capture it because the callback might retain or
schedule the closure. A fresh inline closure may capture `self` only when the
receiving parameter is an explicit immutable borrowed **concrete callable**
type, for example `borrow (() => void)`. That borrow proves non-escape for the
call boundary and retains the callable signature. It does not prove that the
callee invokes the closure exactly once, so it still cannot initialize a blank
`val`. Named callable values do not receive this inline-lambda borrowing
exception and follow ordinary explicit borrow rules.

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
