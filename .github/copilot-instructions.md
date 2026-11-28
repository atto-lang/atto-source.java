# AttoLang-first implementation directive

Follow [the repository-wide agent policy](../AGENTS.md).

**Default to `.atto` for feature implementation.** Standard-library behavior, shared algorithms, high-level actor APIs, reusable logic, examples and guest tests belong in AttoLang guest source; `.java` is not the default feature language just because this is the Java/Truffle backend.

Add or expand Java code only for a necessary compiler, type checker, Truffle/GraalVM/AOT, host-interop, scheduler, security, ownership, OS primitive or bootstrap responsibility. Keep that code narrow and capability-checked. Put composition and guest-visible semantics in `.atto`, not in a trivial `.atto` wrapper over an entire Java feature.

If syntax/compiler/runtime gaps block the `.atto` implementation, extend the minimum host machinery to make executable guest code possible, or explicitly document a temporary fallback and its removal condition.

Every implementation PR should identify new `.atto` modules, justify each material Java addition, describe host/security boundaries and provide *executed* compiler-backed `.atto` test evidence. Do not claim execution from fixtures or zero-step CI. New AttoLang source uses `.atto`; `.ores` is legacy migration-only. Preserve the Rust-not-Python tooling rule and cross-backend contract requirements.

## Additional sealed-AOT / zero-GC admission rules

Read the [local AOT/zero-GC controlled-dynamism checklist](../docs/AOT_ZERO_GC_DYNAMISM.md) and [root AGENTS.md](../AGENTS.md). In sealed guest AOT, reject runtime evaluation/codegen, unknown proxies, unrestricted reflection, runtime class/method synthesis and silent interpreter/JIT fallback *before* executable IR. Generate finite class/proxy dispatch descriptors ahead of time; preserve static vs instance access boundaries.

For actors, closures, suspended task frames and native allocations, require explicit `rt take` / `rt copy` / `rt borrow` / `rt share` ownership, bounded accounting, generation fences and deterministic teardown, including cancellation and restart. Distinguish host Native Image AOT from guest AOT, and guest zero tracing-GC from process zero-GC. Record positive and negative compiler-backed `.atto` tests, exact-head nonzero executed steps, and target-artifact/allocator evidence. A zero-step job is **NOT_EXECUTED**, never guest conformance proof.

These are proposed requirements, not an assertion that the Java backend already implements or verifies every item. Preserve the existing `.atto`-first policy above and the repository's security and Rust-for-tooling rules.
