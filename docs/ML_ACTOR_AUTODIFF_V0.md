# Differentiable actor graphs — reference slice v0 (2026-10-18)

**Status: host-only numerical/protocol reference.** The executable in `contracts/ml/v1/autodiff-reference.mjs` is a deterministic JavaScript model of differentiable actor edges. It does **not** exercise compiled `.ores` code, actual actor messaging, GPU kernels, LLVM lowering, Graal-native-image, shared tensor buffers, or distributed fault recovery. Do not advertise any of those as passing.

## Architecture decision

Tensor values are typed, owned numerical buffers; actors are coarser **computational and ownership boundaries**, not per-element objects. The mathematical autodiff graph and physical actor execution graph must be distinct. The compiler is eventually responsible for tensor-op fusion, VJPs, graph fan-in obligations and device placement. The runtime must preserve observable actor effects and prevent unsafe fusion across state, I/O, or failure boundaries.

A differentiable link comprises two **logical** DATA directions:
- Forward activation, keyed by `(run, step, microbatch, invocation, edge, parameter_version)`.
- Backward VJP contribution, keyed to that exact forward invocation and its parameter snapshot.
Supervisor CONTROL stays separate and must remain deliverable if tensor queues saturate. Messages never confer authority through user-selected actor names. Real transports also need authenticated actor/tenant generation, leases and deadlines (see `docs/ML_ACTOR_PORTABILITY_V1.md`).

Every concrete actor has at most one guest-owned persistent `state { ... }` declaration. Parameters, optimizer moments, activation-tape handles and replay bookkeeping belong inside it, accessed through verified critical state loans. Scheduler correlation, authenticated routing and capability fencing are runtime-owned, not hidden ordinary actor fields. Multiple native carriers are permissible only when configured and concurrency-safe; parameter writes must still be serialized or separately proven disjoint.

## Synchronous training semantics (first target)

1. Supervisor validates and freezes an acyclic typed graph; each parameter tensor has a single owner or designated shard owner. Reject dangling, cyclic, duplicate and unauthorized links.
2. Begin step pins a parameter version. All microbatches of the step use that same version; no optimizer updates during outstanding backward obligations.
3. Forward stages record only required autodiff activations with bounded lifetime/credits. Tensor storage may be moved, frozen-shared or explicitly sharded; a cross-actor mutable borrow is illegal.
4. Reverse-mode VJP traverses the dependency graph. Shared inputs collect **all** child gradients, including residual paths, before their tape can retire. Duplicates and stale versions are rejected.
5. Gradient accumulation is step-local. When all expected microbatches complete, validate the entire proposed optimizer update before making a version visible; retries must not apply it twice.
6. Cancellation abandons uncommitted gradients and leaves the prior parameter version available. Distributed atomic visibility still needs a real durable commit/recovery mechanism.
7. Bounded forward admission must reserve backward/control progress credits; mere bounded mailboxes are insufficient to guarantee deadlock freedom.

## Executable reference

Run from repository root:

```sh
node --test contracts/ml/v1/autodiff-reference.test.mjs
```

The reference supports **CPU f64 vectors** (not general tensors), dense affine stages, ReLU, residual add, and half-sum-of-squares loss. It exercises DAG fan-in, local VJP, finite-difference gradients, max-in-flight admission, correlation, parameter-version fencing, duplicate/replay rejection, atomic local SGD-step proposal, cancellation and a converging training loop. Host synchronous methods emulate message receipt; they are **not** actual concurrent actors. Inputs and parameter snapshots are copied to prevent caller mutation.

Reference assertions are mathematical/protocol unit checks, **not performance benchmarks** or Oreslang language conformance.

## Acceptance gates to implement next

- Tensor IR (dtype/shape/layout/strides/device/lifetime) and certified ownership-aware allocation with checked views, fanout, sharding and explicit transfer.
- Sealed differentiable actor DATA protocol, VJP/JVP registry, static effect purity and non-differentiable side-effect boundaries; integrated compiler lowering in Java and LLVM.
- Independently authored TypeSpec and JSON Schema peer authorities, generated-schema witness and bidirectional validation, including negative fixtures (as required by Zorde policy).
- Prove fan-in completion, exactly-once gradient obligation accounting, backward/control queue progress, parameter-version consistency, stale actor generation rejection and replay safety.
- Compiled `.ores` comparison tests: single-process vs 2/4 actor graphs, JIT/native AOT/hybrid and native LLVM; finite-difference/oracle gradient tolerance.
- After correctness: 1F1B pipeline scheduler, checkpoint/recompute, mixed precision, GPU kernels/collectives, placement, fault injection and instrumented multi-device benchmarks.

Non-goals for v0: training real production models, networked RPC, actual Tensor<f32> values, 1F1B concurrency, cross-process zero-copy, GPU dispatch, automatic source annotation support or verified distributed atomic commits.


## Audit and hardening (2026-10-18)

The reference implementation now refuses unknown/missing keys, symbol or hidden keys, accessor-bearing input records, sparse arrays and non-finite f64 tensor elements. This is **not** a general-purpose JavaScript Proxy sanitizer or an authenticated wire parser: a trusted actor transport must supply real identity, origin, tenant fencing, deadline and authenticated generation.

Resource constraints are intentionally conservative for this host oracle:

| Budget | Maximum |
|---|---:|
| Graph vertices | 128 |
| Total dense weight/bias scalars | 65,536 |
| Scalars per one forward tape (including input + target) | 65,536 |
| Aggregate live tape scalars per step | 262,144 |
| Outstanding forward microbatches | 4,096 (also limited by the configured admission cap) |
| Expected microbatches per step | 4,096 |
| Logical edge trace receipts per step (forward and backward) | 32,768 |
| Retained committed step identities | 4,096 |

The bounded in-memory replay ledger **never evicts** a committed key and refuses new steps when full. It does not survive process restart and cannot serve as a durable distributed exactly-once guarantee. The active state holds at most one synchronous training step at a time, and only zero-based microbatch IDs within `[0, expectedMicrobatches)` are admissible. Admission rejects invalid inputs before saving forward activations. Failed reverse-mode calculations retain the frame for explicit cancellation/recovery; no partial optimizer update is exposed. Forward and backward receipts carry run ID, step ID, microbatch ID, parameter version, source/target edge and input-slot correlation. Dynamic or repeated invocations will additionally require an authenticated execution/tape invocation token in the real actor runtime.

**Determinism:** Per-microbatch parameter VJP contributions are stored separately and reduced in **ascending microbatch ID order**, independent of the order in which backward calls arrive. This fixes order-dependent f64 rounding in the reference; it does not promise device-independent or GPU bitwise reproducibility. Gradient overflow is rejected before local SGD commit rather than saturating or publishing a partial update.

Run the expanded numerical and adversarial regression suite with:

```sh
node --test contracts/ml/v1/autodiff-reference.test.mjs
```

Open admission gates: independently authored TypeSpec / JSON Schema peers and generated witness comparison; real Oreslang parser/typechecker/IR lowering; sealed runtime differentiable envelopes; single actor `state { ... }` critical-loan implementation; CPU/GPU tensor buffer ownership; JIT/AOT/hybrid/LLVM source parity; authenticated remote messaging; progress/fault model checking; durable step commits and recovery. A passing Node reference suite does **not** close any of those gates.
