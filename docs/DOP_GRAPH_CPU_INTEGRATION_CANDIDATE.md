# Owned CPU DOP + typed execution graph integration (draft)

## Exact provenance

This integration branch is contained **inside the original private Java repository**.
Its initial Git merge commit `5652a76d4825bb54a747537ddec933b158770640`
has two parents:

- CPU DOP owned-region integration, Java [#1122](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1122):
  `2b7808285faec4b024923bad1f141e1edac67ddf`.
- Typed CPU/GPU host-graph and Future integration, Java
  [#1096](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1096):
  `7d7a28b0d56d2e6cacb8218b6ddd8d4a6f7c1b2d`.

The integration starts from the current-main DOP tree and overlays eight
unchanged graph source/test/documentation blobs. The graph branch's README is
not overlaid so that the DOP/main README is preserved.

## What actually executes

`DopGraphCpuIntegrationV1.submit(...)` is a **trusted host-only** two-stage
composition:

1. Verify a typed, CPU-only host-i64 scalar graph and the exact input/output
   root, layout, field, half-open row slice, READ/WRITE privileges and liveness.
2. Submit exactly one task to an external bounded trusted CPU executor;
   refuse executor admission after enqueue without executing retained work.
3. On a non-actor-carrier worker acquire DOP's real input read and output
   exclusive write loans throughout `DopKernelIR.executeCpuRegion`.
4. After the region completes and cancellation has not settled the Future,
   execute the typed scalar CPU graph and publish one `OresFuture<Long>`.

No implicit stream is exposed. The result is the *scalar graph result*;
the buffer region is a distinct, prior side effect, not a typed f32 node in
`ExecutionGraphV1`. Cancellation during an already-running kernel cannot
roll back committed host output. Cancellation before worker claim cannot write.

## Tests and evidence

Six new JUnit tests cover owned CPU region and dependency ordering, Future
completion, duplicate executor delivery, pre-execution cancellation,
GPU rejection before admission, invalid footprint rejection, output loan
contention and rollback, and executor rejection after enqueue.

**Locally executed**: OpenJDK 21 compiled the **byte-for-byte committed
integration adapter** (Git blob `b36d265b962fed81afd91a849b52253a3fcdc42a`)
against small, disclosed contract doubles and ran 1,800 control-flow assertions
over 200 iterations. That checks syntax and isolated scheduling behavior only.
It **does not** compile the adapter with real OresFuture/DopKernelIR/JUnit
runtime or demonstrate physical loan isolation in production.

**Still required**: compile/execute the six JUnit tests using the exact
current PR tree under Maven/JDK25, GraalVM JIT and Native Image, plus run
the DOP loan races and full actor suite. Source-org Actions jobs have recently
failed before a runner allocation; no pass may be inferred from zero-step jobs.

## Deliberate language and security holds

- `cpu { ... }`, `gpu { ... }` and `nb cpu { ... }` are NOT lowered from
  real Oreslang AST here. This bridge is host-only, not guest source parity.
- The [empty-domain `void` versus `Unit` decision](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1098)
  is unresolved. The bridge uses explicit host i64 and does not alter that contract.
- Physical GPU dispatch, device allocation/transfers/fences and stream/Channel
  integration remain out of scope.
- Before reuse in other backends, preserve CPU memory ownership and single-flight
  actor semantics. Do not infer LLVM/.NET/Forge/Wasm support from Java host APIs.
- No private source may be mirrored to a different organization while origin
  protection, reviewed destination allowlist, and [P0 exposure incident #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
  remain unresolved. This branch does not authorize an external carrier.

**Merge status:** DRAFT integration candidate. Do not merge ahead of reviewed
source PR #1122/#1096, or claim `.ores` CPU execution without actual compiled
frontend and ownership tests.
