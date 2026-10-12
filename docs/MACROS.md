# Declarative macros: AOT-only expansion, actor safety, and generation fencing

Status: **first-phase implementation**. This document describes only features actually
implemented on the macro feature branch and separates future extensions.

## Parser-validated expression captures

For `$name:expr` parameters, nested macro invocations are expanded at build
time first. The ordinary Oreslang expression parser must then consume the
**entire** captured fragment as exactly one expression. Partial expressions,
statement fragments, free semicolons and dangling operators are rejected
before substitution; a token sequence is not presumed to be an expression.
The completed expanded program still undergoes its normal full type,
ownership, actor-state and effect checks. This is a syntax-validation gate,
not a substitute for typed AST hygiene or semantic conformance.

## Syntax and modes

Oreslang expands **expression-only** declarative macros from tokens **before**
AST parsing, name resolution, type checking, ownership checking, async lowering,
and actor runtime execution. No macro may access a runtime actor heap.

```ores
@expand(aot)
macro_rules! increment {
  ($value:expr) => { ($value + 1) };
}

@expand(rebuildable)
macro_rules! decrease {
  ($value:expr) => { ($value - 1) };
}

define module example as
  pub fnc calculate(): int {
    return increment!(decrease!(42));
  }
end
```

- **AOT** (default; explicit `@expand(aot)`): the macro definition must have
  the same fingerprint across compilation generations. The caller passes the
  preceding macro snapshot to the next compile; removal or mutation fails closed.
  Changing an AOT macro requires starting a fresh compilation lineage / deployment.
- **Rebuildable** (`@expand(rebuildable)`): a separately compiled offline build
  may reexpand changed macro definitions and must re-run type, ownership and actor
  effect checks. This does not authorize source-based hot loading. Publishing a
  new native generation will require a future verified AOT-artifact admission API
  with generation and continuation fencing.
- Both policies expand **only at compilation time**, before the guest AOT build.
  `@expand(jit)` is a compile error. `rebuildable` is not a runtime compilation
  capability: its new generation must complete a separate full AOT build and
  validation before any new native deployment may execute it.
- Macro bodies never gain actor privileges, shared-memory aliases, or exemptions
  from generated-code validation. The compiler validates expanded code normally.

Only file-local macros are supported so far; imported macros, procedural macros,
derive/attribute macros, repeats, binding-generating hygienic expansions,
runtime source rewriting, and shared-region APIs are **not implemented** here.

## Deliberate hygiene limits in the first implementation

The first pass accepts `$name:expr`, `$name:ident` and `$name:tt` captures,
parenthesized macro patterns, and a braced expression replacement. Invocation
delimiters may be `()`, `{}` or `[]`. The implementation rejects:

- introduced *free* identifiers (except a field/method name after `.`, or a
  lexically resolved macro invocation);
- introduced `val`, `let`, `const`, functions, actors, modules, or other
  declarations and statements;
- duplicate **or unused** captures: each parameter must appear exactly once
  in the template, preventing both repeated side effects and erasure of source
  that would otherwise undergo syntax, ownership and actor-state checking;
- unresolved macro names, malformed delimiters, duplicate definitions and
  wrong argument counts/kinds.

Captured `expr` tokens are wrapped in parentheses so a macro cannot silently
change operator precedence when an expression is substituted. Macro definitions
are limited to the file prelude before the first function/type/actor declaration,
including modules using non-braced `define ... as ... end` syntax. Imports may
precede macro definitions. The template validator rejects injected `self`/`super`,
assignment, declarations, statements and control-flow delimiters that could
escape an expression or implicitly capture an actor receiver. Premature EOF
tokens are rejected to prevent hidden trailing input.

These restrictions avoid claiming full hygienic identifier generation before
the AST can represent symbols carrying unique macro-definition identities.
They are *conservative*, not a substitute for full hygiene or type checking.

Bounds: 256 declarations, 32 recursive expansion levels, 128 nested macro
delimiter groups per scanning pass, and up to 50,000 token-work units beyond
the original input size.
Expanders operate on token lists, without runtime callbacks or file/network
capabilities. String lexemes are preserved; generated template token locations
currently point at invocations. Definition-site diagnostic provenance is future
work.

## Generation API

`MacroExpander.expand(tokens, previousSnapshot)` is a pure Java transformation,
returning an immutable token list plus a snapshot of each definition's mode and
SHA-256 fingerprint. The overload without a snapshot is a new compilation lineage.

`Parser.parseWithMacroSnapshot(source, previousSnapshot)` expands and parses
for **trusted build-time callers**, returning a program and a snapshot.
`IncrementalCompiler` persists per-code-unit snapshots transactionally. The
source-based `HotReloadManager` and runtime `OresLanguage.parse()` reject macro
definitions instead of expanding them inside a running guest. The future AOT
artifact loader must independently validate immutable code-unit identities and
generation boundaries. `IncrementalCompiler.clear()` begins a new build lineage;
direct `Parser.parse()` has no persistent lineage, so it must not by itself be
used as proof of safe runtime deployment.

The fingerprint includes mode, ordered captures and template token types/lexemes,
length-framed and hashed as exact UTF-16 code units (including malformed Unicode),
so different token streams cannot be collapsed by UTF-8 replacement encoding.
The source hot loader checks supplied source digests but explicitly refuses
macro definitions; it is **not** a compiled-macro artifact-admission mechanism.
Macros are not runtime values, and actors cannot modify macro definitions in
another compilation lineage. A future artifact loader must establish immutable
manifest identity and generation-fenced publication before reloading generated
code.

## Actor-safety invariants

1. Expand **before** type/ownership/actor-effect checks; never trust a macro to
   declare its own code safe.
2. `Ref<T>` and `WeakRef<T>` remain actor local unless an explicit safe
   transport contract exists; macros do not make them cross-actor handles.
3. Shared actor memory must remain explicit, with read-only/frozen values or
   region-safe synchronized/atomic access. No macro authorizes unsynchronized
   mutable aliasing by invoking host/Java internals.
4. Async macros must satisfy borrow lifetime, pinning, cancellation and
   actor-generation wakeup constraints after expansion.
5. Never run procedural macro code inside the actor runtime. A future proc-macro
   implementation must use a separately sandboxed compile-time worker with a
   bounded, serialized token protocol and no ambient runtime capabilities.

See [actor physical boundaries](runtime/actor-physical-boundaries.md) for the
currently implemented versus pending physical actor protections.

## Next steps

- Integrate snapshot persistence in code-unit reload planning and invalidate
  dependents when rebuildable macros change.
- Add symbol-identity hygiene and expression AST validation, before allowing
  binding-generating declarative macros or repetitions.
- Implement compiler-derived `Sendable`, `SharedSafe`, and `RegionSafe`
  constraints and corresponding synchronized shared-region primitives.
- Add a proc-macro sandbox and expansion-source-map diagnostics.
- Run full GraalVM CI including actor, ownership and hot-reload regressions,
  plus duplicate-evaluation and malformed input fuzzing.

## Validation scope (audit hardening)

Earlier work reported isolated, selected-file macro testing in an external
CI carrier. **Those results do not validate the current PR head.** The present
source-org workflows fail before test steps execute. No complete exact-head
parser/runtime test, host native-image test or guest-level AOT compilation test
has been demonstrated for this revision. Keep the PR draft until all required
gates complete, and never mirror private source to a public repository.


## Runtime hot-loading restriction (P0 AOT enforcement)

The current `HotReloadManager.load(source)` and `load(CompiledUnit)` APIs
stage **source**, not independently verified guest-AOT binaries. Accordingly,
both they and the ordinary GraalVM `OresLanguage.parse()` path reject
source containing any `macro_rules!` definition **before** macro expansion.
They also reject `@FromJson` annotations before the annotation expander
can synthesize accessors in a running guest. The restriction is lexical and
comment/string aware; it does not confuse inert quoted text with an annotation. Neither `@expand(aot)` nor `@expand(rebuildable)` overrides
this restriction. Macro-bearing code must be expanded and compiled offline; a
future signed AOT-artifact loader will be required to admit new native generations.
This rejects a previously permitted source hot-reload use case intentionally.

The `@expand` name is reserved for a directly following `macro_rules!`
definition. Misplaced or malformed `@expand` annotations are compile errors,
not silently ignored metadata. Ungrouped `$x:tt` captures are restricted to
identifiers and literals; grouped captures reject statement/control-flow and
annotation tokens until typed AST-fragment validation is implemented.

## Non-negotiable native AOT admission gate

A source-level annotation or macro cannot require guest JIT compilation, `eval`, runtime
source parsing, live-class mutation, runtime macro discovery, or a runtime code generator.
Every macro invocation must be expanded, semantically checked, and lowered before
shipping the guest executable. A `rebuildable` macro changes only at a *new build*;
immutable running native images never patch macro-expanded machine code.

A Native Image that merely embeds an interpreter is **not** proof of Oreslang guest
AOT. Release evidence must compile and run a generated fixture through the actual
Java/GraalVM guest-AOT path and LLVM backend, compare the expanded contract and
symbol manifest, and reject unsupported target attributes before code generation.
This is a release requirement, not a claim that either backend has passed it yet.
