# AttoLang virtual-actor protected MIR v1 — static contract stage

**Status:** Draft compiler pre-lowering prototype. **NOT_EXECUTED** until exact-head
GitHub Actions admission is restored. Even when this static stage passes, a
`define virtual actor` is intentionally rejected by the production checker
with `VIRTUAL_ACTOR_MIR_UNSUPPORTED`.

## What this patch implements

- `VirtualActorProtectedMirV1.analyze(Ast.VirtualActorDecl)` creates an
  immutable bounded plan for one `int` (checked i64) actor-owned field with
  a constant `Long` initializer and one or more methods matching one
  **exact** compile-time-recognized pattern.
- Each recognized method must be `method(int n) -> void` and contain exactly
  `handle_state(critical-write |s| -> { s.field = s.field + n; });`.
  The checker compares **every lexer-origin token and token kind** including
  delimiters, not merely the presence of `critical-write`.
- No dynamic calls, suspension, nested code, captured variables, address taking,
  state alias escape, guest callback, non-scalar state, signature overloading,
  method injection, nonconstant initializer or unchecked arithmetic is
  permitted in the v1 plan.
- An immutable, **compile-time-only** nominal `VirtualActorRef[Cart]`
  interface descriptor records method name, exact arity, parameter type,
  return type and actor name. `requireCall` rejects wrong method names,
  arities, or argument types statically; neither the plan nor descriptor
  has a public constructor. It has no live `ActorRef`, heap authority or
  generated invocation method. This is NOT a new usable guest type.
- `TypeChecker` runs the strict v1 recognition before its existing
  `VIRTUAL_ACTOR_MIR_UNSUPPORTED` fail-closed gate for virtual actors with
  methods. A valid plan does **not** bypass executable admission.
- Source conformance corpus `contracts/virtual-actor-mir-v1/` contains
  positive typed MIR source and three negative `.atto` cases. Its JSON manifest
  marks runtime execution and backend parity explicitly unavailable.
- `VirtualActorProtectedMirV1Test` checks plan shape and nominal reference
  metadata, checked constant/type admission, exact critical loan structure,
  cancellation of unsafe aliasing/escaping/suspension, forged or missing
  lexer-token provenance, plus that the real type checker still refuses to
  compile/execute the declaration.

## Java exception

This is compiler-only syntax, static shape, and type/ownership validation.
All application behavior remains in `.atto`. No new Java virtual-actor
runtime/scheduler, guest library, dynamic reflective API, or source
rewriting is introduced.

## Why execution remains gated

An immutable syntax plan alone is not a proof of borrow checking,
suspension fencing, handler nonreentrancy or actor-owned memory. The
production compiler must next:

1. Add a compiler-owned lifetime/region with exclusive state-write loan
   checked across all control flow. Ensure no references or closures escape.
2. Lower checked i64 addition into the portable IR with overflow trapping.
   Do **not** implement the guest arithmetic in the Java host.
3. Generate typed logical `VirtualActorRef[T]` calls with statically resolved
   method slots and no reflection. Allocate physical incarnation on first
   invocation through the existing current-main `ActorRuntime` generation
   authority; do not create another generation registry.
4. Initialize actor-owned state *inside* the physical actor heap/stack,
   publish only after successful initialization and durable restore.
5. Hold supervisor CONTROL decision leases through asynchronous completion,
   fence STOP/reclaim, and reject stale physical/generation authority.
6. For persisted state, require a storage-provider-issued CAS epoch; a
   runtime-local generation counter is insufficient after restart.
7. Execute checked positive and negative guest programs on exact Java/GraalVM
   head, Graal Native Image, LLVM, .NET NativeAOT, Wasm and Forge before
   declaring backend parity.

The already-authored Cart guest-transition PR #2826 remains **host-managed**
and does not imply source-level protected virtual actor lowering.

## Verification and merge gate

Workflow `.github/workflows/virtual-actor-protected-mir-v1.yml` checks
exact PR head SHA and Git tree, then requires real Maven/Surefire execution
of the static v1 suite and five existing actor/guest suites. Zero-step jobs
(`steps=[]` or `null`) are runner-admission failures, NOT evidence. Keep
this and the four parent PRs in draft until executed tests and independent
approval.

Private GitHub code may only be mirrored into an approved, private, ID-pinned
destination under `docs/PRIVATE_CI_MIRRORS.md` with authorized security/code-owner
review. The absence of source-org Actions minutes is not permission to
copy source into another organization.
