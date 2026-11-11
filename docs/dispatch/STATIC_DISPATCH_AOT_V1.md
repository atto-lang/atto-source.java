# AttoLang static dispatch / full-AOT admission contract (v1)

Status: **staged compiler policy and guest regression fixture, not an implemented native guest AOT optimizer**.
The Java planner in this change is a pure classifier/oracle; it is **not yet wired into guest IR lowering**. Its host JUnit tests and the new guest fixture are **NOT EXECUTED** in this change unless an independently recorded CI run supplies evidence.

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

## Promotion gates

- [ ] Implement a typed/resolved call-plan IR after macro expansion and ownership/type/effect validation. Make every backend reject unresolved or unsupported call forms before emitting executable output.
- [ ] Wire the plan to Java guest lowering and LLVM, .NET, Wasm and Forge native lowering; record per-backend supported strategies separately.
- [ ] Add closed-world + exact-receiver proofs and invalidation against module generation/hot reload; open-world virtual override must still return child behavior through a base-typed receiver.
- [ ] Capture IR/assembly evidence: direct calls contain no avoidable per-call hash/vtable lookup; indirect calls retain typed slot and bounds/identity checks.
- [ ] Execute `tests/fixtures/dispatch/known_and_virtual_v1.atto` on real compiler binaries for each backend (unsupported backends must reject safely, not pretend parity); record exact source/compiler Git SHA and artifact digest.
- [ ] Compare cold/warm and mono/2-way/4-way/mega workloads, size, allocations, steady-state throughput, p50/p95/p99, code size and reload invalidation. Separate JVM JIT, GraalVM host AOT/interpreter, and native guest AOT.
- [ ] Require macro expansion budgets, hygiene/no accidental capture, cyclic expansion/unsupported syntax fail-closed, and duplicate generic instantiation bounds.
- [ ] Keep private-source CI within authorized private runners; never export Git objects to another organization merely for spare Actions minutes.

## Next implementation ordering

Java parser/typechecker/IR -> LLVM guest AOT lowering -> .NET Native AOT -> Wasm real guest module -> Forge native. Canonicalize the *semantics* first; separate backend capability flags from claims of tested parity. Propose compiler diagnostic `--emit-dispatch-plan` (not yet implemented) for each call site's strategy, proof and generation.
