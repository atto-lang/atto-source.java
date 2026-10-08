# Declarative macros: AOT versus JIT, actor safety, and generation fencing

Status: **first-phase implementation**. This document describes only features actually
implemented on the macro feature branch and separates future extensions.

## Syntax and modes

Oreslang expands **expression-only** declarative macros from tokens **before**
AST parsing, name resolution, type checking, ownership checking, async lowering,
and actor runtime execution. No macro may access a runtime actor heap.

```ores
@expand(aot)
macro_rules! increment {
  ($value:expr) => { ($value + 1) };
}

@expand(jit)
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
- **JIT** (`@expand(jit)`): a new code generation may reexpand a changed macro,
  using the freshly parsed, type-checked, ownership-checked output. **This is not
  a runtime token expander or permission to rewrite running actor frames.** The
  host must publish a new immutable generation and fence continuations/wakeups.
- Both modes expand **at compilation time**. Existing Graal/Truffle JIT compilation
  and runtime optimization is independent of macro expansion mode. A JIT-mode
  macro is usable in AOT binaries; the mode controls *recompilation policy*, not
  whether execution is interpreted, AOT compiled or JIT optimized.
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
- duplicate uses of a captured expression, to prevent evaluation of an affine
  input or side-effecting expression multiple times;
- unresolved macro names, malformed delimiters, duplicate definitions and
  wrong argument counts/kinds.

These restrictions avoid claiming full hygienic identifier generation before
the AST can represent symbols carrying unique macro-definition identities.
They are *conservative*, not a substitute for full hygiene or type checking.

Bounds: 256 declarations, 32 recursive expansion levels, 50,000 output tokens.
Expanders operate on token lists, without runtime callbacks or file/network
capabilities. String lexemes are preserved; generated template token locations
currently point at invocations. Definition-site diagnostic provenance is future
work.

## Generation API

`MacroExpander.expand(tokens, previousSnapshot)` is a pure Java transformation,
returning an immutable token list plus a snapshot of each definition's mode and
SHA-256 fingerprint. The overload without a snapshot is a new compilation lineage.

`Parser.parseWithMacroSnapshot(source, previousSnapshot)` expands and parses,
returning a program and a new macro snapshot. A caller implementing hot reload
must persist the snapshot alongside its code generation and pass it to the next
compile. **Existing hot reload has not yet been wired to persist/pass the snapshot
in this PR.** Consequently the AOT edit guard is enforced by this new API, not
automatically for every reload integration yet.

The fingerprint includes mode, ordered captures and template token types/lexemes.
Macros are not runtime values: no actor may mutate a definition shared by another
actor. This also avoids mixing tokens from two compiler generations.

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
  dependents when JIT macros change.
- Add symbol-identity hygiene and expression AST validation, before allowing
  binding-generating declarative macros or repetitions.
- Implement compiler-derived `Sendable`, `SharedSafe`, and `RegionSafe`
  constraints and corresponding synchronized shared-region primitives.
- Add a proc-macro sandbox and expansion-source-map diagnostics.
- Run full GraalVM CI including actor, ownership and hot-reload regressions,
  plus duplicate-evaluation and malformed input fuzzing.
