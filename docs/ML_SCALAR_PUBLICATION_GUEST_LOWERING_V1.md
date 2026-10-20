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
loan parameter. The version key must be an `int`. A snapshot must capture
at least one non-version scalar; the version field cannot be redundantly
captured as an ordinary scalar. Captured keys must exist in the actor's closed
state schema, must be immutable scalar runtime types (`int`, `bool`, or
`String` in this milestone), and cannot be duplicated. The Java runtime
enforces the same shape independently of compiler admission.

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

Compiler-issued snapshot/publication capabilities are invariant and linear.
They cannot be widened into `Any`/unknown/user nominal/structural types,
wrapped in `Option`/`Result`, stored in lists/tuples/records/classes or
Mutex/SharedMutex, sent through channels, returned/yielded from ordinary
callables, captured by ordinary/deferred/select closures, copied/shared/taken/
borrowed through generic `rt` ownership operations, passed through ordinary
call boundaries, compared with generic binary operators, or observed through
stdio formatting. Snapshots are usable only by the sealed snapshot accessors
and detached preparation; publications are consumable only by the immediate
sealed critical-write publication callback. Runtime ownership intrinsics,
equality/identity comparison, generic print/display, and stdout also reject
direct or nested opaque capability objects defensively if semantic verification
is bypassed. Nested detection is cycle-safe across Option/Result, lists/arrays,
maps, and guest object fields.

The intrinsic names cannot be shadowed by imports, parameters, locals,
destructuring/loop bindings, state-loan binders, or module functions.

## Actor lifecycle barrier compatibility

The executable fixture uses the canonical zero-argument lifecycle barriers
`await worker.get_ready()` and `await worker.get_done()`. This stacked
compiler branch now lowers those calls to the existing ActorRef ready/done
futures and rejects arguments. Legacy `.ready`/`.done` property access is
left intact temporarily on this stack to avoid unrelated compatibility churn;
removal belongs to the broader actor lifecycle convergence work.

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
the active loan name, preserves the snapshot capability identity across the one
sealed read-callback escape, rejects publication from a nonmutable/read loan,
and consumes the publication capability exactly once at the source level.
The actor-state contract independently permits the raw state-loan parameter to
cross only the two sealed bridges—`state_snapshot` and
`publish_scalar_publication`—and forbids either bridge from nested closures.

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
- module/import/local/parameter/state-loan intrinsic-name spoofing;
- capability laundering through `Option`/`Result`;
- generic `rt copy`, stdio observation, and equality/operator use;
- version-only and duplicate-version snapshot shapes;
- nonzero-arity `ActorRef.get_ready/get_done` calls;
- Java host/foreign-actor publication preparation and post-retirement snapshot use.

## Current verification boundary — 2026-10-19

The current PR head is authored but **not compiler-verified**. Exact commit
identity and the newest source-org run IDs are pinned in the pull-request
description rather than self-referenced here (changing this document changes
the Git commit). At the verification boundary, source-org fast CI, full CI,
and Graal conformance continue terminating before recording any job steps
(`steps:null`). Those are infrastructure admission failures, not passing or
failing Java tests.

An independently authored source-free model in
`ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra#29`
executed on funded hosted capacity. Run `39346666583` completed successfully
with **15/15 tests passed**, including 20,000 seeded snapshot/TOCTOU schedules,
retirement interleavings, lowering admission, namespace reservation, strict
snapshot shape, canonical lifecycle arity, the closed capability-operation
model, and recursive runtime observation denial. It contains no Oreslang private
source and is supporting design evidence only; it does **not** certify this Java
tree, Graal execution, Native Image, LLVM, or the guest fixture.

Exact-head Maven/JUnit/GraalVM execution remains a release gate. A GitHub job
that terminates with no recorded steps is infrastructure evidence, not compiler
evidence. Cross-organization private-source mirroring remains prohibited while
the repository's private-CI security incidents/gates remain unresolved.
