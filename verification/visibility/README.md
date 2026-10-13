# Private-by-default visibility: executable finite model

`VisibilityExports.tla` is a TLA+ transition model of **cross-source-unit
exports**, not a model of every compiler language feature.

It enumerates all six combinations of: synthetic root, private named module,
public named module × public/private member. Every scan order is represented
by the state graph. The safety invariants forbid a private member or any member
of a private named module from appearing in an external export set. The
completion invariant requires the exported set to be *exactly* the allowed set.

Run the dependency-free exhaustive state explorer (also part of the `visibility-proof` CI job):

```sh
python3 verification/visibility/check_model.py
```

It explores all 64 reachable abstract states and 192 scan transitions, then
injects a deliberately faulty policy that drops the module visibility check.
That mutation must produce a private-export counterexample.

To run with the TLC model checker (install TLA+ tools separately):

```sh
cd verification/visibility
java -cp /path/to/tla2tools.jar tlc2.TLC -config VisibilityExports.cfg VisibilityExports.tla
```

For changes to the language's semantic access rules, update the independent
oracle in `ModuleVisibilityProofTest` and the model together, and verify
the model's safety properties plus real compiler/runtime integration tests.
The Java production rule lives in `dev.oreslang.ast.VisibilityPolicy`.
Both import resolution and GraalVM runtime must call that shared policy.

**Scope limitation:** TLC exhaustively checks this finite abstraction; it
does not establish full compiler correctness, absence of reflection/FFI
escapes, or a machine-checked refinement mapping between the source
implementations and the TLA+ transition relation.
