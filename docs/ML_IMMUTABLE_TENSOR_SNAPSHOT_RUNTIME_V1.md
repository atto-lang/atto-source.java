# Immutable actor tensor snapshot runtime v1

**Status:** Java runtime substrate, stacked on the scalar atomic-publication work.
This is not yet source-level `TensorF64` ownership, Graal guest execution, LLVM
parity, GPU buffer leasing, or a distributed optimizer protocol.

## Contract

`SealedActorState` now admits a runtime-only `tensor:f64` state slot and a
`TensorSnapshotF64` value with these invariants:

- shape and f64 storage are defensively copied before publication;
- backing arrays are private/final and are never returned directly;
- public array access is copy-out only; scalar reads are read-only;
- every snapshot is bound to the issuing `SealedActorState`, actor identity,
  run, graph, actor incarnation, and parameter version;
- snapshot methods require the issuing actor's currently admitted turn, but
  do not require ownership of the mutable state-loan carrier gate; detached
  immutable reads therefore remain valid if another permitted carrier briefly
  owns state, while aliases handed to another actor still fail authority;
- an immutable snapshot may leave a synchronous state READ/WRITE callback and
  remain readable after the state loan closes;
- ordinary `Access.write` and generic scalar publication cannot write
  `tensor:f64`; the only admitted mutation is a prepared tensor publication;
- preparation accepts an issuer-bound `ScalarSnapshot` rather than raw
  caller-supplied transaction counters; version/revision and the captured
  run/graph/incarnation values are derived from that one authenticated
  snapshot, closing stale-computation rebasing;
- every non-bootstrap update must also name the issuer-owned published
  `TensorSnapshotF64` generation used for computation; its
  version/run/graph/incarnation metadata must match the authority snapshot,
  and publication rechecks that the exact source snapshot object is still the
  tensor stored in actor state before swapping generations;
- preparation is forbidden while the current carrier owns a state loan and
  rechecks actor retirement after allocation/copy before capability escape;
- publication revalidates mutation revision, run, graph, incarnation, and
  version under the critical WRITE loan before one reader-visible state-map
  swap;
- invalid/stale candidates leave the existing tensor and version unchanged;
- rank, extent, element count, finite-value and retained-snapshot byte/count
  budgets are bounded;
- retained snapshot accounting is internally synchronized, so actor
  `maxThreads > 1` cannot corrupt the weak retention ledger;
- runtime retirement revokes and zeroes every still-live tracked snapshot.

The weak retention ledger is deliberate: the state root does not keep obsolete
immutable generations alive solely for accounting, but snapshots that are
still held by actor computations remain tracked and count against the bounded
retention budget.

## Why this is separate from the current library TensorF64

The current `oreslang-tensor/src/tensor.ores` class exposes
`pub val List<float> values`. A library wrapper cannot turn that mutable
backing into a compiler proof. This Java substrate therefore does not claim
that existing source tensors are safe actor snapshots.

The compiler promotion path is:

1. introduce/lower a source-level deeply readonly tensor snapshot value;
2. require its construction from owned or copied storage outside a state loan;
3. permit that specific immutable value to escape a read loan;
4. forbid mutable List/Array aliases, borrowed views, pointers, device handles,
   callbacks, and state capabilities from entering the snapshot;
5. lower publication to the exact run/graph/incarnation/version/revision gate;
6. implement the same representation and rules in LLVM;
7. execute real two-actor forward/backward training with snapshot generations
   retained only while a bounded activation/gradient computation needs them.

## Tests

`SealedActorTensorSnapshotTest` uses the real `ActorRuntime` and verifies:

- shape/storage/nonfinite/extent rejection;
- caller-array mutation after preparation cannot change the snapshot;
- copy-out mutation cannot change backing data;
- immutable snapshot escape from a closed state loan;
- ordinary tensor write and scalar-publication bypass rejection;
- incomplete/foreign snapshot-authority rejection, missing/foreign source
  tensor rejection, and same-carrier live-loan preparation rejection;
- stale mutation-revision failure atomicity;
- cross-actor read denial;
- retirement revocation;
- reflection guard that array backing fields remain private/final and only
  defensive-copy array-returning methods are public.

**Evidence boundary:** test source is committed but exact-head JDK25/Maven and
GraalVM execution have not yet been observed on an authorized runner. A
`steps:null` Actions job is not test evidence. No private source was exported
cross-organization by this change.
