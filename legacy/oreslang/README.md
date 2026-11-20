Critical: New repo is in the github.com/atto-lang org in atto-lang/atto-source.java

> **AI agent instructions (organization-wide):** Before working on code, issues, or pull requests, read and follow the [organization `AGENTS.md`](https://github.com/ores-truffle-oreslang/.github/blob/main/AGENTS.md), including its **merge-first stealth-development policy**. Honor any repository-local `AGENTS.md`, explicit holds, and GitHub-enforced rules.

# AttoLang Java/GraalVM reference backend

## AttoLang migration and authoritative language contracts (2026-11-18)

**AttoLang** is the current language name (**formerly Oreslang**). This `ores-truffle-oreslang/oreslang-source.java` repository retains the historical name and implementation context; the AttoLang-named counterpart is **[atto-lang/atto-source.java](https://github.com/atto-lang/atto-source.java)**, the destination for new AttoLang development. This legacy Java/Truffle tree and the AttoLang Java repository may differ in code and tests. Verify exact commit trees and guest execution before considering a feature migrated.

The **[atto-spec](https://github.com/atto-lang/atto-spec)** repository now owns normative language syntax and semantics, rather than this backend README, the old Oreslang repository names, or Java/Truffle behavior alone. Portable, positive **and negative** language tests belong in [atto-platform-conformance](https://github.com/atto-lang/atto-platform-conformance); compare implementations via [atto-compilers-parity-testing](https://github.com/atto-lang/atto-compilers-parity-testing). [atto-common-source](https://github.com/atto-lang/atto-common-source) owns portable `.atto` libraries. Backend-specific compiler code stays in its corresponding `atto-source.*` repository.

**Current source-design directions (targets, not claims this backend implements them):** canonical `.atto` for new portable source, `snake_case` function/method names, ownership operations `borrow!`, `take!`, `share!`, `copy!`, and `copy!!`; [implicit borrowing for nonprimitive arguments](https://github.com/atto-lang/atto-spec/blob/main/language/implicit-argument-borrow-v1.md) is still a design target subject to compiler verification. [File/module `define const`](https://github.com/atto-lang/atto-spec/blob/main/language/file-module-const-v1.md) supports proposed constexpr values **and** non-throwing, one-time runtime `os.read_env` initialization. [The proposed operator contract](https://github.com/atto-lang/atto-spec/blob/main/language/bitwise-and-stream-operators-v1.md) uses `<<<`, `>>>`, and `ashr` for shifts and reserves `<<`/`>>` for directional stream I/O; it is **not** automatically supported by legacy backends. [Shared ownership](https://github.com/atto-lang/atto-spec/blob/main/language/shared-ownership-and-guards-v1.md) does not grant unsynchronized mutable aliasing. Check spec documents for proposal/admission status before changing a parser or example.

**Compatibility and verification:** Keep existing `.ores` fixtures, `oreslang-*` commands, package IDs, fixed ABIs, and wire identifiers wherever still used; an AttoLang name change alone does not prove that a renamed CLI, syntax form, AOT target, actor isolation, zero-GC runtime or guest feature works. Retain exact legacy commands below when they describe this checkout. Do not use Python for project scripts, tests, CI helpers or tooling; use Rust for supporting tooling/tests where practical, without misrepresenting the backend implementation language. Record source commit/tree, target/toolchain, real positive/negative **compiled guest execution**, and `passed`/`failed`/`unsupported`/`not executed` for every claimed parity result. Host-only tests or workflows with zero executed steps are **not** compiler parity evidence.

In general, strive to implement language features in .atto files not .java files (more attolang less java). In other words, for atto core and atto std lib more .atto files and fewer .java files. We also need to keep/maintain parity with oreslang-source.llvm and oreslang-source.net and oreslang-source.wasm, etc. The language specification, not any one backend, defines portable semantics; Java is a reference implementation only where verified.

AttoLang is the current public language name (formerly Oreslang). This repository remains the Java/Truffle reference compiler and runtime. Existing Java packages, `oreslang` compiler paths, `.ores` fixtures and external interfaces remain compatible during the rename. New `.atto` acceptance must be demonstrated by real parser/compiler and execution tests, not this README update.

Use [AttoLang Brand Compatibility v1](https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/main/contracts/v1/attolang-brand-compat-v1.json) as the migration policy. Do not rename repository paths, package coordinates, command names, ABI or wire-format identifiers as part of a branding-only change.

AttoLang (formerly Oreslang) is a statically typed language with a GraalVM/Truffle implementation with nominal typing by default, explicit structural-call opt-ins, actor-oriented concurrency, hot-loadable code generations, and deny-by-default isolate capabilities.

This repository contains the Java/Truffle reference implementation. The AttoLang counterpart projects are [atto-source.llvm](https://github.com/atto-lang/atto-source.llvm), [atto-source.wasm](https://github.com/atto-lang/atto-source.wasm), and other `atto-source.*` backends. The old `oreslang-source.*` names remain historical checkout references.

Do not use python for anything, use Rust instead of python to support the language.


**Experimental declaration substrates:** [State container and virtual actor VM admission](docs/STATE_CONTAINER_VIRTUAL_ACTOR_SUBSTRATES.md). Guest `define state container` and `define virtual actor` are not yet compiled; this implementation currently supplies bounded access and activation capability primitives with distinct fail-closed parser diagnostics. Do not treat this as runtime parity.

Notes on the compiler and runtime:

- Strive for min gc or zero gc where possible. Having separate heaps/arenas per actor helps reduce gc overhead. Our borrow checking should reduce need for gc to zero.
- Legacy ownership syntax in this checkout includes `rt copy`, `rt share`, `rt take` and `rt borrow`; current AttoLang source design prefers `copy!`, `copy!!` (deep copy), `share!`, `take!`, `borrow!`, and implicit checked parameter borrows. Check [atto-spec](https://github.com/atto-lang/atto-spec) and backend admission tests before rewriting historical fixtures.
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
- Historical local declarations use `val`, `const` and `let`; the current [file/module `define const` target](https://github.com/atto-lang/atto-spec/blob/main/language/file-module-const-v1.md) also permits static immutable constants initialized by constexpr or a single guarded runtime `os.read_env` read, pending compiler conformance;
- private actor state is logically confined in the current backend; shared actors may access explicit synchronized shared cells; protected per-actor heaps are under development;
- immutable/sendable values may be message-passed, and explicitly frozen regions may be shared read-only;
- host access is denied and Oreslang APIs are capability-gated by default; the current actor kinds alone do not establish an OS sandbox;
- JIT, AOT/interpreter, and AOT-host + guest-JIT hybrid execution profiles;
- file-granular incremental compilation with stable code-unit/package identities and reverse-dependency invalidation;
- flat optional file namespaces and flat modules (neither may nest);
- class-level `static fnc` functions separated from receiver methods;
- first-class function aliases/types and canonical `|args| -> expression` lambdas (block-form acceptance is backend-specific);
- lexical closures with persistent captured environments;
- affine ownership, moves and checked borrows are intended; the legacy `rt copy`/`rt share`/`rt take`/`rt borrow`/`rt mut borrow` spellings below are historical backend syntax. Modern target source uses `copy!`/`copy!!`/`share!`/`take!`/`borrow!` and implicit nonprimitive argument borrowing, where separately implemented and tested (not Rust `&`/`*` borrow syntax)
- hot reload creates a fresh versioned guest context/generation without requiring FFI or dynamic native libraries;
- direct method calls reuse shared class method definitions; instance/actor methods are direct-call-only, and callbacks use explicit lambdas instead of implicit bound-method values;
- multiple named modules may appear in one source file;
- explicit `return` statements unless same-line lambda or void return type;


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

CPU/GPU execution expressions, nested placement and `nb`/stream parity: see [proposed v1 contract](docs/CPU_GPU_NB_GRAPH_V1.md). This is **not** a claim that the backend executes GPU or streamed source blocks.

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

## Legacy repository scope and contribution routing (2026-11-18)

**Historical checkout:** `ores-truffle-oreslang/oreslang-source.java`. **AttoLang destination:** [`atto-lang/atto-source.java`](https://github.com/atto-lang/atto-source.java).

**Scope:** Java/Truffle frontend and runtime, GraalVM/Native Image experiments, JVM-specific compiler integration. The historical repository and new counterpart may not contain identical commits, pull requests, issues, or artifacts; a same-suffix repo is not proof of an in-place GitHub ownership transfer or identical Git history. Compare exact SHAs and migrate outstanding work deliberately; open new AttoLang design changes against [atto-spec](https://github.com/atto-lang/atto-spec) and new backend work in the appropriate AttoLang repository. The former plan to transfer `oreslang-source.java` under its unchanged legacy name is superseded as a naming destination by `atto-source.java` (without implying the old GitHub repository itself has moved).

Keep the [historical organization migration tracker](https://github.com/ores-truffle-oreslang/.github/issues/26) and [review runbook](https://github.com/ores-truffle-oreslang/.github/pull/25) for migration/security context, not as proof of a completed transfer. Preserve access controls, private source provenance, branch protections, PR/issue history and CI evidence; do not assume an existing `atto-*` repository is a verified byte-for-byte transfer. Host-only or source-free canaries do not prove guest code execution or parity.

