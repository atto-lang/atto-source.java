# ML scalar publication guest lowering v1

**Status:** draft Java/Graal compiler/runtime slice, stacked on ML runtime PR #1509.
This contract is intentionally scalar-only. It does not claim tensor ownership,
authenticated gradient transport, LLVM parity, or durable optimizer commits.

## Source contract

The compiler exposes eight sealed names. They are compiler intrinsics, not
ordinary library functions, and their names are reserved in module and lexical
value scopes:

- `state_snapshot(stateLoan, "version", "field", ...)`
- `snapshot_int(snapshot, "field")`
- `snapshot_bool(snapshot, "field")`
- `snapshot_string(snapshot, "field")`
- `snapshot_version(snapshot)`
- `snapshot_revision(snapshot)`
- `prepare_scalar_publication(snapshot, "field", value, ...)`
- `publish_scalar_publication(stateLoan, publication)`

A canonical scalar optimizer step is:

```ores
private step(): bool with state.read, state.write, alloc {
    val snapshot = self.handle_state(critical read |s| -> {
        return state_snapshot(s, "version", "weight", "optimizer");
    });

    val version = snapshot_version(snapshot);
    val weight = snapshot_int(snapshot, "weight");
    val optimizer = snapshot_int(snapshot, "optimizer");

    val publication = prepare_scalar_publication(
        snapshot,
        "version", version + 1,
        "weight", weight - 1,
        "optimizer", optimizer + 1
    );

    return self.handle_state(critical write |s| -> {
        return publish_scalar_publication(s, publication);
    });
}
```

## Static guarantees

`state_snapshot` is valid only inside the currently active lexical
`self.handle_state` callback and its first argument must be that exact state
loan parameter. The version key must be an `int`. Captured keys must exist in
the actor's closed state schema, must be immutable scalar runtime types
(`int`, `bool`, or `String` in this milestone), and cannot be duplicated.

The resulting snapshot type is compiler-internal. Source cannot name or
construct it. Snapshot accessors require a literal captured field and an exact
matching scalar type. Version and mutation revision have dedicated accessors.

`prepare_scalar_publication` is valid only after the calling state loan is
released. Replacement keys are literal, unique, scalar-typed, and every
non-version replacement must have been captured by the originating snapshot.
The version key is mandatory. The runtime performs the authoritative exact
`+1` version check and mutation-revision fence.

The resulting publication type is also compiler-internal and move-only.
`publish_scalar_publication` requires the exact active state-loan parameter
inside a critical WRITE/EXCLUSIVE callback. Ownership consumes the publication
at the call site; the runtime additionally rejects replay.

Compiler-issued snapshot/publication capabilities are invariant. They cannot be
widened into user nominal/structural types. The intrinsic names cannot be
shadowed by parameters, locals, destructuring bindings, or module functions.

## Runtime lowering

The Java evaluator lowers the sealed calls directly:

1. `state_snapshot` -> `SealedActorState.Access.snapshotScalars`.
2. Accessors -> the issuer-bound immutable `ScalarSnapshot`.
3. `prepare_scalar_publication` -> `ScalarSnapshot.preparePublication`,
   which delegates to the exact issuer's hardened preparation path from #1509.
4. `publish_scalar_publication` ->
   `SealedActorState.Access.publishScalars` under the live write loan.

No Oreslang source obtains the `SealedActorState` issuer object, raw backing
map, mutation revision setter, or Java publication constructor.

## Effect and ownership contract

`state_snapshot` contributes `state.read` and `alloc`.
`prepare_scalar_publication` contributes `alloc`.
Snapshot scalar/version/revision access is detached immutable computation.
`publish_scalar_publication` contributes `state.read` and `state.write`.

The effect verifier rejects candidate preparation while a state loan is live and
rejects publication outside the active loan. The normal handle-state mode check
therefore rejects publication from read callbacks.

The ownership checker independently binds `state_snapshot` and publication to
the active loan name, rejects publication from a nonmutable/read loan, and
consumes the publication capability exactly once at the source level.

## Tests

`tests/fixtures/ml-scalar-publication-runtime.ores` is a real guest fixture.
It snapshots version/weight/optimizer, computes outside the loan, prepares a
new scalar generation, publishes it atomically, then prints the resulting
weight/version/optimizer.

`MlScalarPublicationGuestLoweringTest` contains the positive parser/type/
effect/ownership + Graal guest gate and compiler-negative cases for:

- snapshot outside the state loan;
- wrong snapshot accessor type;
- unknown captured field;
- preparation while a state loan is live;
- blind replacement of an uncaptured field;
- publication from a read loan;
- non-publication payload;
- module/local/parameter intrinsic-name spoofing.

Exact-head Maven/JUnit/GraalVM execution remains a release gate. A GitHub job
that terminates with no recorded steps is infrastructure evidence, not compiler
evidence. Cross-organization private-source mirroring remains prohibited while
the repository's private-CI security incidents/gates remain unresolved.
