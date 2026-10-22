# ML actor portability v1 — design-stage RFC (Java #743)

This PR pins an intentionally small, **closed** JSON Schema for versioned ML actor DATA envelopes (request, result, cancel, error), a resource request record, and a separate pure BigInt tensor validator. These are **not** source lowering, proven TypeSpec parity, a GPU runtime, or production policy enforcement.

## Required invariants

- Actor identity binds tenant, actor ID, incarnation and generation. Receiver authenticates tenant and validates fencing; guest-supplied fields confer no authority.
- Correlation ID and deadline bind ordinary DATA calls. Supervisor CONTROL is separate and not represented as an ordinary inference reply.
- Tensor descriptors carry dtype, shape, element strides, bounded byte length, layout, device, ownership and lifetime. No native pointer or unscoped IPC handle is a wire identity.
- Cross-actor mutable borrow is refused; only moved or frozen immutable data may cross with generation-scoped leases. One physical executing carrier per actor must be enforced by the runtime separately.
- Same-process moved values, same-node shared-memory handles, GPU IPC/device memory and remote serialized handles are distinct trust boundaries. None is implicitly zero-copy.
- CPU/NUMA/GPU requests are untrusted placement hints until an authenticated host resource broker enforces limits and issues a scoped lease.
- Backend capability checks fail closed absent explicit verification; these files do not mark any backend as verified.

The JSON Schema checks structure only. The companion semantic guard checks rank/strides, integer overflow, buffer size and unadmitted GPU. Production must run **both** and include independently authored TypeSpec parity tests.

## Open acceptance gates

1. TypeSpec declaration and differential positive/negative golden cases.
2. Bind validators to Java source compiler and actor runtime, with allocation/alias lifetime checks.
3. Compiler source-level tests for Java JIT, AOT/guest interpreter and hybrid. LLVM remains unsupported pending evidence.
4. Authenticated tenant-scoped routing, generation fencing, cancellation, deadline semantics and resource broker.
5. Joint proof with typed pipelines and supervision (Java #643/#641, LLVM #177/#175).

Reference-only checks: node --test contracts/ml/v1/validate.test.mjs. Passing these cannot be reported as successful compiled Oreslang ML source tests.

## Audit hardening (2026-10-12)

- The reference validator requires plain JSON records and dense, own-data arrays: getters, inherited prototypes, hidden fields, symbols and sparse descriptors fail closed. This is a shape check for JSON-parsed input, **not** a sanitizer for arbitrary Proxy objects.
- Cross-actor mode is now the default. Borrowed local aliases and actor-turn-lifetime descriptors are forbidden across the boundary; local-only mode must be requested explicitly.
- Backend feature names cannot use Object.prototype meta-keys. A `verified` property is **not a signed capability** or production authorization without a trusted host-side authority check.
- 26/26 isolated pure JavaScript checks passed against branch contents under a V8 synchronous harness; this is not a Node runner or compiled Oreslang parity result.
