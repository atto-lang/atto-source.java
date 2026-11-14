# Oreslang annotation admission v1 — common backend corpus

All five source backends carry the identical `annotation-admission-v1.tsv`. The corpus is deliberately an **admission floor**, not a claim that the backends have equivalent annotation AST, macro expansion, serialization or security semantics.

- `shared-deny`: all backends must reject the source before executable code is produced (Java via checked parsing / expansion; LLVM and native bootstraps via source admission; Wasm at the preflight bridge).
- `bootstrap-deny`: LLVM, Wasm, .NET and Forge must reject. Java may retain a user-defined annotation as metadata; its end-to-end semantics are not certified by this corpus.
- `shared-allow`: all frontends must continue to parse an ordinary scalar source, including annotations appearing only in lexical comments.

TSV has exactly three tab-separated fields, with a literal `\\n` encoding source line breaks. Every test runner rejects malformed rows, empty IDs, duplicate IDs or unknown kinds. Backends must not pass a negative test merely because a different backend is unavailable. For native/AOT failures, verify nonzero exit and no generated IR/artifact when the compiler harness supports that check.

## Compatibility policy

Java/GraalVM currently supports typed `@FromJson` field expansion and selected AST/type metadata; the other bootstraps do not. The recognized LLVM `@ActorMaxThreads` syntax is capability-guarded and is **not** generalized annotation support. Until a shared closed-world registry, checked argument types, target restrictions, hygienic expansion and generated-code rechecking are implemented, unknown or security-relevant annotations must not be accepted as authorization or optimization claims.

Do not silently lower unsupported metadata as a no-op, infer authority from marker strings, execute runtime reflection, or treat parse-only metadata as enforcement. Exact-head compile/execute tests and nonzero-step CI are mandatory before promoting drafts.
# Annotation admission contract v1 (2026-10-29)

Status: security/semantic baseline; **not** a claim that all annotations execute on all backends.

## Invariants

1. An annotation is identified by its resolved, package-qualified declaration, not an untrusted display name, string, or Unicode/case-folded approximation.
2. Every annotation has a declared **target** (module, class, actor, field, method, function, parameter, local binding or type), immutable **phase** (source metadata, compile-time transform, type/effect proof, or backend ABI), typed argument schema and explicit repeatability policy. Unrecognized annotations requiring semantics must fail closed.
3. Annotation arguments are compile-time constants evaluated under finite step, depth, byte and expansion budgets. Evaluation may not use I/O, clocks, randomness, unsafe, network, mutable state or a runtime actor. Named arguments are canonicalized, have unique labels, and cannot collide with positional bindings.
4. Duplicate nonrepeatable annotations, unknown arguments, missing required arguments, malformed delimiters and wrong targets are compile errors. Repeated `@Ret` is not last-write-wins.
5. Annotations must be retained in typed AST/HIR metadata until a verified consumer handles them. A lowering pass must either produce a compiler-auditable proof/typed IR transformation or reject the program; silently dropping an annotation or treating it as decoration is forbidden.
6. Expansion happens before type checking, ownership/borrow checking and effect checking, with a recheck of generated declarations. Expansion cannot bypass visibility, sealed actor state, permission scopes, Sendable/Serializable transport, supervisor CONTROL rights or module/package boundaries. Generated symbol names must not collide with user symbols and repeated compilation must be deterministic.
7. An annotation cannot grant capabilities or authorize unsafe code. No `@Trusted`, `@Unsafe`, `@AllowAll`, `@Untrusted`, `@RequiresPermission` or similar spelling can bypass sandbox isolation, permissions or FFI entry-point gates.
8. Hot reload and incremental caches bind annotation declaration versions, expanded-AST fingerprints, dependency signatures and effect/ownership proof dependencies. Changing or deleting an annotation invalidates affected proof caches.
9. Backends must reject annotations they cannot lower **before executable IR, assembly, CIL, Wasm or a published artifact**. Host-only metadata parsers and contracts do not count as executable feature support.
10. Failure evidence requires an exact source+compiler SHA, invoked compiler command, nonzero exit with diagnostic, **no executable artifact**, and executed runner steps. Runner-admission failures are NOT execution proof.

## Admission v1 test vectors

Negative:
- `@Ret(int) @Ret(bool) fnc main(): int { return 1; }` — duplicate return contract.
- `@Ret(int) define module demo as end` — callable annotation on module.
- `@FromJson("id") define module demo as end` — JSON field annotation on module.
- `@Critical(1) fnc main(): int { return 1; }` — reject until checked budget, scheduler and time/accounting lowering exist.
- `@repr(c) fnc main(): int { return 1; }` — reject until target-layout ABI lowering exists.
- `@RequiresPermission("ffi") fnc main(): int { return 1; }` — cannot grant/skip permission checking.
- `@ActorMaxThreads(MaxActorThreads.HIGH) define actor Worker as end` — reject without proved concurrent actor-state isolation.
- `@UnrecognizedMacro fnc main(): int { return 1; }` — reject as unresolved compiler-affecting annotation unless explicitly declared metadata with a verified schema.

Positive controls only where implemented:
- A plain, supported scalar function must still compile and execute unchanged.
- In Java, `@BuildMetadata define module demo as end` is currently retained as opaque source metadata; it must never be interpreted as an executable security or ABI directive.
- In Java, `@FromJson("user_id")` is a field-specific, compiler-validated expansion, not permission or unsafe-code authority.
- LLVM may admit the restricted `@ActorMaxThreads(MaxActorThreads.LOW)` actor setting only with native actor checks; this does not authorize Wasm actors.

## Backend status as audited

- **Java/GraalVM:** selected source annotations/expansions; improve duplicate and wrong-target rejection. Arbitrary opaque metadata retention is not proof of semantic annotation execution.
- **LLVM:** limited native actor attribute admission; unsupported attributes are errors. Most declarative features remain not lowered.
- **Wasm:** LLVM compiler bridge; reject all annotation syntax at the Wasm source preflight until independently verified Wasm guest lowering is available.
- **.NET Native AOT:** narrow scalar compiler; reject annotation source syntax, do not simulate annotations using C# runtime attributes.
- **Forge:** narrow independent bootstrap; reject annotation source syntax, do not simulate annotations using C++ host attributes.

Promotion requires both byte-identical contract files and executable, compiler-owned positive/negative fixtures per backend. Unimplemented features must remain explicitly unsupported, not no-op metadata.

## Shared executable admission ledger

The identical file `contracts/v1/annotation-admission-v1.cases` contains 17 bounded,
base64-encoded UTF-8 source vectors, with exact pipe-separated columns:

`id|java|llvm|wasm|net|forge|source_utf8_base64`

An expectation of `pass` is **phase-scoped**. Java means parser admission
only; LLVM means actual host/wasm32 IR generation; WASM means only preflight
admission before the pinned LLVM bridge; .NET means acceptance by both scalar
and structured parsers; Forge means the real scalar/native-call executable
entrypoints return 42. No `pass` at a source-preflight phase proves that a
native binary was emitted or executed.

The all-backend negative set includes unimplemented deadline, permission,
unsafe authority, role/middleware, ABI layout, inline/derive, duplicate
return-type and invalid-target annotations. Two Java-only positive
controls preserve its existing `@Ret` callable parsing and opaque
`@BuildMetadata` module retention without advertising runtime execution.

Every parity harness must enforce expected result, full corpus cardinality,
unique case IDs and canonical UTF-8/base64 decoding. Native compiler
harnesses must additionally reject stdout IR or on-disk artifacts on
negative outcomes. Positive controls must not be satisfied by mocks or
unexecuted Actions jobs.
