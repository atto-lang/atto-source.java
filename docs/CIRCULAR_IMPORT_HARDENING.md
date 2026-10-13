# Circular imports and initialization (Java backend audit)

## Normative, implemented behavior

- A source import adds a dependency edge and exposes only the selected exported declarations. An import statement is **not** a function invocation.
- Every reachable source unit is parsed and compiled before the linked program executes.
- Cycles are legal. The compiler condenses cycles into strongly connected components (SCCs), computes a dependency-first order and links **all** code units before the loader runs any `init()` hook.
- Imported functions execute only when called; importing, resolving, or linking a declaration does not execute its body.
- Distinct overloads of a public function in the same module are one imported symbol family, not ambiguous duplicate imports. Cross-module name collisions remain ambiguous.
- An explicitly declared `init()` hook is a **startup lifecycle action**, not a top-level import expression. **Important:** the current multi-file loader invokes the `init()` hook of **every** loaded file, including imported files, before entry `main()`. This is eager application startup, *not* lazy per-import initialization.
- Module and root `init()` hooks must satisfy the existing closed synchronous lifecycle signature. A failed initializer transitions its evaluator to FAILED, and entry `main()` must not run.
- The host/public Java bridge cannot call into a merely linked, initializing, or failed unit. It requires the READY phase.

## Behaviors to reject

- Missing, ambiguous, or non-public named imports; direct self-imports (including filesystem aliases).
- Executing `main()` before successful initialization.
- Host-side calls bypassing initialization through a public-function invocation.
- Re-entering an already INITIALIZING unit, or retrying a failed `init()`, rather than observing partially initialized state.

## Test matrix

- Two-way A ↔ B and three-way A → B → C → A cycles.
- A call can cross a cycle only after all units are linked.
- Two public overloads of the same function name in one module must be importable by their arity.
- A private function cannot become importable merely by participating in a cycle.
- LINK_ONLY does not execute `init()`, and explicit INIT_ONLY runs it once.
- A failed INIT_ONLY makes public host invocation fail closed.

## Additional hardening (not implemented by this document)

1. **Strict lazy dependency initialization:** If the language contract means *even imported `init()` hooks must not run until first call*, replace eager startup semantics with a specified lazy state machine, SCC-level cycle/reentrancy policy, and cross-thread deadlock/failure tests. That is a behavior change relative to the current regression example. Do not silently conflate it with the existing link barrier.
2. **Export visibility audit:** Actor and type-alias selectors require a comprehensive visibility contract. The present AST does not carry a visibility bit for `TypeAliasDecl`; fix across parser, compiler, runtime and both backends rather than only in graph validation.
3. **Stress/property tests:** Shuffle source and import iteration order, exercise diamond graphs, aliases, repeated imports, very deep graphs, concurrent initialization attempts, and incremental rebuilds of nodes inside cycles.
4. **Native parity:** The LLVM frontend currently has a token for `import` but does not implement a multi-file import/linker, so cycle parity is not yet established.

Do not describe these outstanding items as verified by the current PR.
