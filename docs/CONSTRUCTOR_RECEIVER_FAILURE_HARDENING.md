# Constructor receiver, dispatch and failure cleanup hardening

## Implementation state

This draft stacks on Java constructor reconciliation PR #1284.
It includes hardening for static and interpreter dispatch paths and a
backend-neutral **cleanup plan**. A cleanup plan is not a resource-drop
implementation; a backend must lower its actions and prove exactly-once
effects before claiming deterministic native destructor semantics.

## Receiver authority and migration

The parser records whether the first constructor parameter was the special
`self` binder. This marker does **not** count toward constructor arity:

```ores
define class Example as
    val int value;
    constructor(self, int n) { self.value = n; }
end
```

Current production executable admission is still compatible with older
source files that omit the binder yet use `self`. We must not silently break
existing public examples and tests during migration.

`ConstructorValInitialization.checkExplicitReceiver(klass)` is a deterministic
strict validation entrypoint. It checks all constructors and rejects any
field read/write through `self` if the active constructor lacks a leading
`self`. Pure sibling delegation needs no receiver binder unless its tail
uses `self`. The opt-in gate is for migrations and tests; it is **not yet
the default** in the public parser, type checker or runtime.

Migration to explicit-only receivers:
1. Record warnings/violations under strict mode on existing source fixtures.
2. Rewrite constructor signatures with `self` where receiver access occurs;
   preserve caller-visible overload arity.
3. Run existing unit tests, compiled examples, linked files and native tests.
4. Change default compiler admission and remove the legacy runtime binding.
   Only after all backends and tests pass may a language version make the
   stricter rule mandatory.

## No dispatch on an unpublished receiver

The constructor CFG rejects `self.method()` even after all required fields
have been initialized. Successful field initialization is not publication.

The checker also rejects direct bound method references to instance methods
declared in the current class. The interpreter separately refuses a method
reference if a member is not a known field in the effective inheritance
hierarchy of the *same* constructing object.

The Java interpreter defends three execution paths:
- ordinary source call preparation;
- evaluated/suspendable call preparation (with rejection before arguments);
- member-reference materialization.

This is defense in depth for linked or unchecked ASTs; it does not authorize
instance-method dispatch in an ordinary constructor. Calls on unrelated
already-constructed objects are unaffected.

## Failed-constructor cleanup plan (semantic IR, NOT execution)

For a failed and still-unpublished construction region, the builder:

1. lists registered DEFER/FINALLY actions in LIFO order;
2. emits FIELD_DROP for each initialized field in reverse declaration order;
3. guards FIELD_DROP when an assignment interval includes both zero and
   nonzero execution counts;
4. optionally drops a proven-initialized base subobject;
5. reclaims object storage last.

A field with multiple assignments still receives **one final-slot drop**
in the failure cleanup plan. Dropping values overwritten by earlier
assignments must occur at those assignment sites, not here.

The builder rejects incomplete field layouts, already-published objects
and cleanup stacks containing preexpanded field/base/storage-drop actions.
It never claims that runtime destructor calls or native ownership drops
have been emitted. Backends must later prove actual unwind semantics,
effect ordering, idempotent drop guards and panic/raise boundaries.

## Acceptance criteria before promotion

- Compile and execute exact-tree JDK25/Graal JVM tests.
- Execute the new `ConstructorDispatchSafetyTest`,
  `ConstructorExplicitReceiverMigrationTest` and
  `ConstructorFailureCleanupTest` alongside existing suites.
- Verify no missing test reports and count actually executed tests.
- Prove Native Image AOT/hybrid behavior; do not substitute public
  source-free Java capacity canaries for private compiler tests.
- Implement backend cleanup lowering and run exception/fault-injection
  tests before declaring native cleanup parity.
- Keep PR draft while jobs fail before checkout / no runner assignment.
