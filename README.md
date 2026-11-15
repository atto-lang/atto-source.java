# AttoLang Java/GraalVM reference backend

In general, strive to implement language features in .atto files not .java files (more attolang less java). In other words, for atto core and atto std lib more .atto files and fewer .java files. We also need to keep/maintain parity with oreslang-source.llvm and oreslang-source.net and oreslang-source.wasm, etc. The oreslang-source.java is typically the leader.

AttoLang is the current public language name (formerly Oreslang). This repository remains the Java/Truffle reference compiler and runtime. Existing Java packages, `oreslang` compiler paths, `.ores` fixtures and external interfaces remain compatible during the rename. New `.atto` acceptance must be demonstrated by real parser/compiler and execution tests, not this README update.

Use [AttoLang Brand Compatibility v1](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/main/contracts/v1/attolang-brand-compat-v1.json) as the migration policy. Do not rename repository paths, package coordinates, command names, ABI or wire-format identifiers as part of a branding-only change.

Oreslang is a statically typed GraalVM/Truffle language with nominal typing by default, explicit structural-call opt-ins, actor-oriented concurrency, hot-loadable code generations, and deny-by-default isolate capabilities.

This repository contains the Java/Truffle reference implementation. The LLVM version is in oreslang-source.llvm and WASM version is in oreslang-source.wasm etc etc.

Do not use python for anything, use Rust instead of python to support the language.


**Experimental declaration substrates:** [State container and virtual actor VM admission](docs/STATE_CONTAINER_VIRTUAL_ACTOR_SUBSTRATES.md). Guest `define state container` and `define virtual actor` are not yet compiled; this implementation currently supplies bounded access and activation capability primitives with distinct fail-closed parser diagnostics. Do not treat this as runtime parity.

Notes on the compiler and runtime:

- Strive for min gc or zero gc where possible. Having separate heaps/arenas per actor helps reduce gc overhead. Our borrow checking should reduce need for gc to zero.
- Borrow checking uses reserved keywords `rt copy, rt share, rt take, rt borrow`
- We want full AOT for GraalVM with Native Images so we can get single binary executable files.
- Strive for hard real time not just soft-real time by minimizing JIT (or having JIT switched off for certain paths) and moving towards full AOT.
- Use data-oriented design and data oriented programming - be mindful of using AoS, SoA, AoSoA and SoAoS where possible.
- Use 32 bit pointers instead of 64 bit pointers for allocation/heaps. Having separate heaps per actor helps with this. The 32 bit pointers should be implemented so that it stays 32 bit even on Arm-64 machines, etc etc.
- OresLang must be built around excellent tree-shaking so we can get final binary executables as small as possible and reduce startup overhead etc.

The language is intentionally opinionated:

- optional semi-colons, but in some places are necessary for compiler to insert;
- static nominal typing by default, with explicit structural compatibility at selected call boundaries using reserved "structural" keyword;
- private functions by default (`fnc`), with `pub` for exported functions;
- class methods omit `fnc` and have an implicit `self` receiver;
- class construction uses a single explicit `constructor(...)` declaration (never a Java-style class-name constructor); top-level classes/constructors are file-private, while module classes/constructors are private by default and may be `pub`;
- classes cannot nest inside classes; class bodies contain fields, an optional constructor, methods, and `static fnc` members only;
- one return value only (tuples/arrays/records are ordinary single values);
- `val`, `const`, and `let` are the only variable declarations;
- private actor state is logically confined in the current backend; shared actors may access explicit synchronized shared cells; protected per-actor heaps are under development;
- immutable/sendable values may be message-passed, and explicitly frozen regions may be shared read-only;
- host access is denied and Oreslang APIs are capability-gated by default; the current actor kinds alone do not establish an OS sandbox;
- JIT, AOT/interpreter, and AOT-host + guest-JIT hybrid execution profiles;
- file-granular incremental compilation with stable code-unit/package identities and reverse-dependency invalidation;
- flat optional file namespaces and flat modules (neither may nest);
- class-level `static fnc` functions separated from receiver methods;
- first-class function aliases/types and block-only `|args| -> { ... }` lambdas;
- lexical closures with persistent captured environments;
- affine ownership, move checking, borrows, immutable-by-default parameters, and `Type mut name` owned-mutation syntax; source ownership uses `rt copy`, `rt share`, `rt take`, `rt borrow`, and `rt mut borrow` (no `&`/`*` pointer/reference/dereference syntax)
- hot reload creates a fresh versioned guest context/generation without requiring FFI or dynamic native libraries;
- direct method calls reuse shared class method definitions; instance/actor methods are direct-call-only, and callbacks use explicit lambdas instead of implicit bound-method values;
- multiple named modules may appear in one source file;
- explicit `return` statements unless same-line lambda or void return type;

The first implementation is developed on a feature branch and will land with an executable Truffle skeleton, grammar/specification, examples, tests, and CI.

Native inbound HTTP capabilities and actor-owned request transfer are documented in [docs/HTTP_SERVER.md](docs/HTTP_SERVER.md).

The [Java/.NET host import, native AOT and actor-memory acceptance contract](docs/JAVA_DOTNET_AOT_ACTOR_HEAPS_V0.md)
distinguishes existing trusted Java library imports, the bounded experimental
`import dotnet` path, true native binaries, per-actor unsigned-32 offsets,
guest borrow checking, and measurable near-zero-GC goals. Native Image host
AOT and Epsilon host-only tests are **not** full guest AOT or GC-free proofs.

The implemented actor scheduling guarantees, native descriptor-transfer experiment,
and remaining physical-isolation work are tracked in
[Actor physical boundaries](docs/runtime/actor-physical-boundaries.md).

Async filesystem, TCP and HTTP client APIs: [usage and execution guarantees](docs/ASYNC_IO.md).

Core assertions: see [Assert API and trap behavior](docs/ASSERT.md).

## Rust-only tooling policy (no Python)

**Python is prohibited in this organization.** Do not add, maintain, invoke, generate, or require Python code or Python-based scripts, helpers, tests, code generators, build utilities, automation, CI steps, notebooks, or runtime dependencies. This includes `.py`, `.pyi`, `.ipynb`, `python` / `python3` commands, `pip`, `uv`, `poetry`, `pytest`, and Python GitHub Actions setup steps.

Implement tooling previously written in Python in **Rust** instead. Preserve behavior and test coverage while migrating; use `cargo fmt`, `cargo clippy`, and `cargo test` for Rust code. Existing Python must be inventoried and ported, not merely deleted if it implements required behavior. Changes introducing Python must be rejected in review and CI. References discussing the prohibition are allowed in documentation; they are not an authorization to execute Python.

This rule applies across all repositories, including compiler backends, examples, benchmarks, workflows, and documentation. Language implementations written in Java, LLVM/other native languages, .NET, etc. remain in their existing implementation languages; **the Python-to-Rust mandate applies to Python tooling and Python implementations**.

## Canonical AttoLang source files

**Implementation policy (agents and contributors):** Put guest-visible features and library logic in `.atto` by default; reserve `.java` for justified compiler, runtime, host-interop, security and bootstrap code. See [the AttoLang-first agent directive](AGENTS.md) and [Copilot instructions](.github/copilot-instructions.md). This is a source-of-implementation rule, not just an extension rule.

New AttoLang modules, tests and examples use the `.atto` extension. The Java frontend accepts `.atto` as native Ores-language source while retaining `.ores` as a legacy compatibility extension. Extensionless imports prefer `.atto` before `.ores`. See [the source-extension contract](docs/ATTO_SOURCE_EXTENSION.md).

## Experimental `--analyze` frontend integration

`oreslang-compiler --analyze example.atto` (legacy `.ores`) invokes
the existing linked source-admission frontend, performs the production
type/ownership analysis, and emits conservative
`ORES-ANALYZE-V0` name/arity records without executing the guest.

This is the first Java/GraalVM adapter for
[platform-conformance compiler-analysis v0](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/348)
and native LLVM [PR #1375](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1375).
It **does not** certify arbitrary transitive `throws`/`raises`/`panics`,
actor supervisor routing, imports, dynamic invocation, checked arithmetic,
or full source parity. Exit 0 is restricted to source-admitted closed
scalar literal/parameter/direct-call expressions; unsupported or unknown
effects are inconclusive (2) and invalid source is a failure (1).
The independent `atto test --effects` tool remains advisory.

**Evidence pending:** new implementation and JUnit fixtures have not
been executed against Maven, Native Image, or LLVM's real binary at this
exact head. Run `mvn test -Dtest=CompilerEffectAnalysisV0Test`, the
real cross-backend conformance fixtures, and pinned-binary
no-guest-execution/no-artifact checks before promoting this mode.

## Repository ownership and migration (2026-11-08)

**Current home:** `ores-truffle-oreslang/oreslang-source.java` (legacy Oreslang organization). **Language name:** AttoLang; historical `oreslang-*` CLI/package identities and existing `.ores` files remain compatibility interfaces until independently migrated and tested.

- **What belongs in this repository:** Java/Truffle/GraalVM reference compiler, interpreter, source admission, actor/runtime host primitives and their executable tests.
- **What belongs elsewhere:** LLVM/.NET/Wasm implementation details, managed cloud control plane, or portable core/std semantics unnecessarily duplicated in Java. Shared portable AttoLang language semantics and core/std should be authored in canonical `.atto` modules and reconciled with `oreslang-common-source`; indispensable host compiler, scheduler and ABI primitives remain backend-specific here.
- **Organization boundary:** language/compiler/tooling/conformance work belongs to [`atto-lang`](https://github.com/atto-lang); commercial deployment, identity, billing, hosted runtime and multi-tenant cloud operations belong to separately governed [`atto-co`](https://github.com/atto-co). The existence of similarly named `atto-*` repositories is **not** proof of a Git repository transfer or tested feature parity.
- **Proposed first migration:** transfer this *existing repository identity* to `atto-lang/oreslang-source.java` retaining its name and history, **only after** individual source/target owner and independent security approvals. No direct transfer to `atto-co` is authorized in the current migration wave. A future product carve-out requires separate approval.

**Maintainer cutover instructions:** coordinate under [migration issue #26](https://github.com/ores-truffle-oreslang/.github/issues/26) and the private [migration runbook draft #25](https://github.com/ores-truffle-oreslang/.github/pull/25); check stable repository numeric ID, exact head SHA/tree, branch protection/rulesets, reviewers, GitHub Apps, secrets, deploys, dependencies, releases, packages, Pages and remote URLs. Transfer using an approved GitHub ownership operation rather than mirroring private source; validate the unchanged ID/tree and rerun exact-head CI at destination. **`steps=[]` or `steps=null` means `NOT_EXECUTED`; host-only tests do not establish native AttoLang guest parity.** Do not combine transfer with forced `.ores`→`.atto` syntax changes, repo renames, CI policy relaxation or AOT/zero-GC claims.
