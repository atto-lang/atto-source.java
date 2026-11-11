# AttoLang static dispatch / full-AOT admission contract (v1)

Status: **Java reference evaluator integration staged; native guest AOT optimizer NOT IMPLEMENTED**.
Java `TypeChecker` now calls `AotDispatchPlannerV1.classify` after successful callable resolution, attaches identity-bound `CheckedDispatchPlansV1` metadata to free/static/instance calls, and publishes an immutable per-program plan snapshot after type validation. `OresEvalRootNode.Evaluator` verifies the snapshot against its exact linked code image *once*, before exposing it to actor carriers, and consumes it without a global registry lock on guest calls. Only same-code-image free functions, qualified static methods, or instance receivers with the **exact checked runtime class** can skip ordinary lookup; polymorphic subclasses, reloaded generations and foreign code keep the existing slot/binding lookup. Calls containing argument spreads do not receive plans.
This is an interpreter-side binding optimization, **not** evidence of native guest LLVM/Native Image/Native AOT dispatch lowering or performance gain. Its new host JUnit tests and guest fixture are **NOT EXECUTED** unless an independently recorded run supplies evidence.

## Core invariant

Prefer statically resolved calls whenever the checked program proves their target, but preserve language behavior. **Every supported construct must work with JIT disabled.** Java/GraalVM Native Image of an interpreter is host AOT, not proof that the AttoLang guest program was emitted as native machine code. Do not report a performance gain until emitted IR and benchmarks prove it.

## Required post-typecheck call plan

1. Expand hygienic declarative/typed/derive macros entirely at build time, with deterministic resolution, source mapping, fuel/recursion/size budgets, and explicit target-feature admission. Typecheck/borrow-check/effect-check the expanded AST again. Never run a macro, eval, emit machine code, or discover reflection metadata at guest runtime.
2. Resolve a stable fully qualified symbol/owner and the canonical callable slot. Class methods presently overload by name + explicit arity; static and instance domains remain disjoint. Do not make runtime parameter types choose a different overload. The staged nominal-enum ABI is NOT the production dispatch contract until full compiler/runtime parity exists.
3. Classify each call with **trusted compiler evidence**, not a source-supplied assertion:
   - Free function or static method with frozen, fully linked generation: **DIRECT_SYMBOL**.
   - Instance method with exact concrete receiver proof, or closed-world single-implementation proof, in a frozen code generation: **DIRECT_SYMBOL**.
   - Open/interface/base-typed receiver without such proof: **METHOD_SLOT** using the runtime type and precomputed slot.
   - Named call whose target can change across hot-loaded generations: **VERSIONED_LOOKUP**; generation identity must be checked. An old caller may use its own frozen generation only when module semantics explicitly pin it.
   - Escaping closure/bound method/unknown function value: **INDIRECT_CALLABLE** unless a separate proven closure analysis specializes it.
   - Cross-actor send/ask: **ACTOR_MAILBOX**, regardless of the handler's compile-time class. The receiver's private handler may later be statically bound *after* mailbox admission.
   - Foreign/host boundary: **HOST_CAPABILITY_BOUNDARY**; dispatch optimization cannot bypass permissions or sandbox admission.
   - Unexpanded macro or unresolved named callable: fail before native IR or executable artifact emission.
4. Lower `DIRECT_SYMBOL` to a direct IR call, `METHOD_SLOT` to a typed slot table (or a proven equivalent), and versioned calls to generation-checked linkage. Do not synthesize a direct call solely because a JIT observed a monomorphic site. Optional guarded devirtualization must have a correct AOT fallback.
5. Preserve argument evaluation order, panic/throw/raise, checked arithmetic, visibility, lifetimes, borrows, drops, capability checks, safepoints, and exactly-once mailbox semantics. No class/actor state may escape isolation because of inlining or macro expansion.

## Current audited limits (2026-11-10)

- **Java:** `CallableSelector` and `OresEvalRootNode` already construct immutable effective arity-based class method slots. `docs/performance/TRUFFLE_DISPATCH_ALLOCATION_AUDIT.md` says devirtualization and allocation wins are unproven. `Ast.ClassDecl` does not yet expose a general sealed/final proof for this planner.
- **LLVM:** `MethodResolver.cpp` rewrites bounded native class methods to named calls. `docs/NATIVE_CLASS_METHODS.md` explicitly excludes inheritance, virtual/static/generic methods in its current native slice. `docs/ANNOTATION_MACRO_AOT_CONTRACT.md` records incomplete typed/derive macro support.
- **.NET:** Typed compiler IR and nominal method-slot ABI contract exist, but `nominal_method_slot_abi_v1.json` currently says `reject-unimplemented` for .NET guest nominal dispatch.
- **Wasm:** Real wasm32 macro parity requires the independently pinned compiler/linker/native guest gate described by `docs/MACRO_WASM32_PARITY_V1.md`; normal tests alone do not certify it.
- **Forge:** `compiler/call_native.cpp` supports a narrow direct scalar-call stage; it is not general method dispatch.


## 2026-11-10 proof-store hardening and honest verification

- `CheckedDispatchPlansV1` now has **no global per-call proof registry**. The type checker accumulates AST-identity plans only in its own local `IdentityHashMap`, checks repeated-call target identity, and publishes a deep-copied immutable identity snapshot only after `checker.validate(program)` succeeds. A failed or partial type check has no published call plan. Re-publication to the same Program checks both exact call identity and exact referenced target identities; record structural equality cannot substitute for symbol identity.
- All plans check matching lexical call shapes and reject argument spreads before publication. A malformed batch leaves the program snapshot unchanged. At the evaluator boundary, direct bindings must still pass local code-image slot checks; source-level checks cannot grant actor or host privileges.
- Two **byte-exact, SHA-verified** Java production components were locally compiled using OpenJDK 21: `AotDispatchPlannerV1.java` (blob `bf32c8d627afbc41d9fe6958fe77aa34881a522a`) and `CheckedDispatchPlansV1.java` (blob `0eebc62039bd9143053d49df61ae2b012ae25e15`). Their **32/32 standalone policy/registry checks passed**, using a *minimal test-only AST stub*. This proves Java syntax and limited classifier/storage behavior only; it does **not** prove compilation against the real `Ast.java`, production `TypeChecker`, Truffle runtime, the `.atto` guest fixture, or native AOT. The full JUnit and JIT-disabled profile tests are **NOT EXECUTED** at this checkpoint.
- **Private CI export denied:** current source `main` reports `protected: false` through GitHub's branch API. `.github/private-ci-approved-repositories.txt` and `.github/private-ci-carrier-contracts.json` do not authorize this PR and test plan on an independently approved carrier. Per `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md` V3, source export is forbidden until protected-main and exact independent review requirements are met. Do not bypass this by using another organization's Actions minutes or by publishing temporary refs. Source-free tests can execute elsewhere but cannot establish guest compiler parity.

## Promotion gates

- [x] **Java-only staging:** emit checked free/static/instance `CallPlan` metadata after macro expansion and type validation; bind it to an exact checked program and verify the source symbol/slot once at evaluator creation. Ownership admission remains mandatory before execution. This is **not** a general native-IR call plan.
- [ ] Promote these typed call plans to the native backend IR after ownership/effect validation; enforce fail-closed rejection of unresolved or unsupported emission forms in every backend.
- [ ] Wire the plan to Java guest lowering and LLVM, .NET, Wasm and Forge native lowering; record per-backend supported strategies separately.
- [ ] Add closed-world + exact-receiver proofs and invalidation against module generation/hot reload; open-world virtual override must still return child behavior through a base-typed receiver.
- [ ] Capture IR/assembly evidence: direct calls contain no avoidable per-call hash/vtable lookup; indirect calls retain typed slot and bounds/identity checks.
- [ ] Execute `tests/fixtures/dispatch/known_and_virtual_v1.atto` on real compiler binaries for each backend (unsupported backends must reject safely, not pretend parity); record exact source/compiler Git SHA and artifact digest.
- [ ] Compare cold/warm and mono/2-way/4-way/mega workloads, size, allocations, steady-state throughput, p50/p95/p99, code size and reload invalidation. Separate JVM JIT, GraalVM host AOT/interpreter, and native guest AOT.
- [ ] Require macro expansion budgets, hygiene/no accidental capture, cyclic expansion/unsupported syntax fail-closed, and duplicate generic instantiation bounds.
- [ ] Keep private-source CI within authorized private runners; never export Git objects to another organization merely for spare Actions minutes.

## Next implementation ordering

Java identity-bound interpreter proof (this PR; awaiting executable tests) -> checked native call-plan IR -> LLVM guest AOT lowering -> .NET Native AOT -> Wasm real guest module -> Forge native. Canonicalize the *semantics* first; separate backend capability flags from claims of tested parity. Propose compiler diagnostic `--emit-dispatch-plan` (not yet implemented) for each call site's strategy, proof and generation.

### Generic calls remain outside proven direct-symbol dispatch

The checked Java `TypeChecker` now records local direct/slot plans only
when the selected declaration and its nominal receiver class have **no
unresolved generic parameters**. A source-level resolved generic call is not
yet a uniquely **emitted specialization**; its method name and source ABI
must not be treated as proof that a monomorphized AOT body exists. The
ordinary checked interpreter can still evaluate supported generic calls.

A future compiler integration may re-enable such calls only **after**
full typed, post-macro, borrow/effect/capability-approved instantiation
closure, unique emitted-symbol assignment, precise ABI checks, and the
bounds from common-source #265. Java's Truffle guest evaluator dispatch
is not machine-code NativeAOT specialization. The tests in
`CheckedDispatchPlanIntegrationV1Test` assert the generic exclusion.

### Canonical scalar generic runtime-parity fixture

`tests/fixtures/dispatch/generic_i64_reuse_v1.atto` executes two explicit
`identity<int>` calls, expecting stdout `2022`. The exact checked-AST
integration test requires **no** direct-symbol plan for either generic call
until the Java compiler actually emits a concrete, closed specialization.
`AotDispatchGuestV1Test` exercises the fixture with Java guest JIT
enabled and with the guest JIT-disabled execution profile.

This is a **semantic compatibility gate**, not Java NativeAOT generic
machine-code emission. The LLVM branch independently introduces a
precompiled `int`-only specialization. The Java backend must not consume
or trust a fabricated LLVM emitted symbol without its own code-generation,
signature and source-identity checks. Current Java tests are committed
but require an admitted JUnit runner at the exact PR head.
