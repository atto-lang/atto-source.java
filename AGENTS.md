<!-- org-merge-review-policy:begin -->
> **Agent/PR governance:** Read [Oreslang central AGENTS.md](https://github.com/ores-truffle-oreslang/.github/blob/main/AGENTS.md). **Drafts:** at 72h of the CURRENT Draft cycle (creation if opened Draft; latest genuine Ready→Draft otherwise), automatically mark Ready when exact-head material tests PASS and no `keep-as-draft`, `not-ready`, or legacy `keep-draft` label is present. Recheck labels and CI before promotion; **Ready review clock then starts at zero**. Ready <72h: Codex/ChatGPT + Claude/Opus + `ORESoftware` + `the1mills`; Ready 72–240h: one verified independent reviewer and passing CI, no conflicts, mandatory competing/overlapping PR audit with semantic updates to current or competing PRs; Ready ≥240h: fresh post-boundary four-party review. Returning to Draft resets Ready cycle. Never bypass GitHub protections or game lifecycle events. **Verified counterpart repository:** [atto-lang/atto-source.java](https://github.com/atto-lang/atto-source.java); inspect open/draft PRs in both orgs before coding, promoting or merging.
<!-- org-merge-review-policy:end -->

# Agent directives: AttoLang-first implementation

These instructions apply to the entire `oreslang-source.java` repository, including AI coding agents and human contributors. **Implement AttoLang behavior in `.atto` source, not in new `.java` source, whenever the behavior can run as AttoLang guest code.** This is a code-placement rule, not merely an extension-renaming rule. Do not rename existing Java implementation files to `.atto`.

## Default decision: guest first

1. **Choose `.atto` by default** for new standard-library modules, collections, reusable algorithms, transformations, high-level actor/pipeline APIs, protocol and application logic, examples, and guest-visible regression cases. A feature whose public contract is expressible in AttoLang should have its substantive implementation in AttoLang, even though the reference compiler/runtime is hosted in Java.
2. **Use `.java` only for genuine host/compiler work:** lexing/parsing, AST and type checking, code generation and Truffle integration, GraalVM/Native Image bootstrapping, host interop, privileged OS/native calls, low-level schedulers, allocation/ownership and security enforcement, or minimal runtime primitives currently impossible to implement safely in guest code.
3. **Keep host bridges thin.** Expose the smallest capability-checked primitive from Java, then implement composition, policies and public algorithms in `.atto`. Do not move guest-level functionality into Java solely for speed of development, convenience, or because the backend already has Java classes.
4. **If the guest language lacks a required construct,** first consider extending the parser/typechecker/runtime sufficiently to compile the `.atto` implementation. A temporary Java fallback is acceptable only when an actual bootstrapping constraint prevents this; document its removal condition and do not silently present the fallback as guest execution.
5. **Use `.atto` for all newly authored guest source, tests and examples.** `.ores` is for legacy compatibility and migration tests only. Preserve existing import-resolution and ABI compatibility; see `docs/ATTO_SOURCE_EXTENSION.md` and the brand-compatibility contract.
6. **Keep AttoLang portable.** Shared guest APIs must not depend on Java-only types or hidden Java behavior. Record the corresponding LLVM, Wasm, Forge/Cranelift and .NET contract/fixture work, distinguishing parity proposals from actually executed evidence.

## Change review requirements

Every feature PR touching implementation code must state:

- **Guest implementation:** which `.atto` modules implement the feature, or why no guest implementation is yet possible.
- **Java exception:** each new or materially expanded `.java` component and the specific compiler, privileged host, AOT or bootstrap responsibility requiring Java. A generic “performance” justification is insufficient without a benchmark and a portable contract.
- **Boundary/security:** any new host capability, ownership transfer, actor isolation or sandbox interaction, including how guest access is controlled.
- **Execution evidence:** the real `.atto` compiler/checker and guest-execution tests, including expected outcomes and exact status. A source fixture, Java-only unit test, source-free smoke test or zero-step CI result is **not** proof that the AttoLang guest code executes. Label unexecuted checks as **NOT EXECUTED**.

For mixed-language changes, prefer the sequence: define the public `.atto` API and fixture; add only the missing Java primitive/compiler support; execute positive and negative guest tests; then record cross-backend follow-up. Do not start by implementing the entire feature in Java and leave `.atto` as a thin wrapper.

## Existing implementation and tooling

This directive is forward-looking. Do not mass-convert or delete required Java compiler/runtime code. Migrate suitable existing Java guest-library behavior incrementally, with equivalent tests and explicit compatibility checks. The repository's **no-Python / Rust-for-tooling** policy remains in effect and does not authorize moving guest semantics from `.atto` into Rust.

**Review rule:** a guest-expressible feature implemented principally in `.java` without a documented technical exception does not meet this repository's implementation standard.

## Full guest AOT and zero-GC controlled-dynamism directive (proposed policy)

**Review all new features against [organization policy PR #38](https://github.com/ores-truffle-oreslang/.github/pull/38) and [language-spec chapter PR #18](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/18), and the [local checklist](docs/AOT_ZERO_GC_DYNAMISM.md).** In sealed full-guest-AOT builds, no runtime eval/source compilation, new classes/methods, unbounded host reflection, monkey-patching or implicit interpreter/JIT fallback. Compile static proxy forwarding, virtual dispatch metadata, generics/macros and closure state machines ahead of time; reject unsupported shapes before IR/code emission.

Every allocation, closure capture, actor heap and suspended frame needs an owner, bounded accounting, release strategy and cancellation/restart/generation-fencing proof. Ban arbitrary strong-reference cycles without an approved lifetime/region plan; long-lived actors must reclaim retired allocations without waiting for actor termination. Channels/pub-sub carry Sendable data, never functions/closures, raw pointers or hidden host capabilities. Preserve explicit `rt take`/`rt copy`/`rt borrow`/`rt share`, no implicit conversions/operator overloading/`%`/single `^` (but `^^` is bool XOR only) and no implicit nested `Future`/`Option` flattening.

**Review gate:** PRs declare execution profile (JIT/hybrid, host AOT, sealed guest AOT, governed precompiled-module AOT) and memory claim (managed host, zero guest tracing-GC, process zero-GC); add positive and negative `.atto` compilation/execution cases, runtime cancellation/allocator stress, immutable source/fixture IDs and stepful evidence. `steps=[]` and `steps=null` remain **NOT_EXECUTED**. Do not claim cross-backend parity from this documentation.
