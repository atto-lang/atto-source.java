# Circular imports, lazy execution and initialization (Java backend)

## Language contract implemented on this draft branch

- An `import` declaration records a dependency and exposes **only** the
  permitted public symbols. No imported function, constructor, actor hook or
  file-level `init()` is run by importing, parsing or linking.
- Every reachable unit is parsed and type-checked before any guest code runs.
  Cyclic dependencies are legal; strongly connected components (SCCs) are
  fully linked before the entry unit begins initialization.
- **Only the entry Ores file** initializes before its `main()`.
  Other units initialize on the first cross-unit execution, not merely because
  they appear in the import graph. A Java entrypoint triggers Ores init only
  through an explicit mixed-language public invocation.
- Importing a callable or class *value* does not by itself initialize it.
  Calling the function, invoking a static function or constructing an imported
  class does. Accessing an imported module's **eager field initializer**
  requires the imported unit's initialization first; these fields are a
  separate member-access case, not inert callable declarations.
- The phase state machine is `CREATED → LINKED → INITIALIZING → READY` or
  `FAILED`. Each `init()` runs at most once per linked code unit and may
  not be called by guest code. An unsuccessful `init()` permanently poisons
  that evaluator for that context.
- Reentrant lazy init into a unit already INITIALIZING is an error, even if
  the call happens through a legal import cycle. No partially initialized
  exports are exposed. Imported functions may call back into previously
  READY units.
- All lazy initializers in an isolate share a **nonblocking**, reentrant
  startup gate. A simultaneous attempt from another Java thread fails fast
  with a concurrent-startup diagnostic rather than acquiring opposite
  per-unit locks and potentially deadlocking an actor carrier. Callers must
  arrange a later retry if startup contention occurs. This is deliberately
  **not** a transparent asynchronous waiter yet.
- Overloaded public functions in a single module are imported as a single
  callable family, keyed by arity. Name collisions between separate modules
  remain ambiguous.
- `main()` and direct public host invocations require an already READY unit,
  so embedding callers cannot bypass the startup barrier. The mixed Java
  bridge explicitly executes the target unit's init command before
  invoking its exported function.

## Regression coverage added

- Bidirectional A↔B and A→B→C→A compilation/link/call tests.
- Importing B without calling it leaves B.init unexecuted.
- A→B first call runs B.init exactly once, then B may call back into READY A.
- Recursive initialization fails instead of observing partially initialized A.
- A failing B.init prevents B's imported function body from running.
- Link-only, init-once, failed unit and direct public-host gate tests.
- Overloaded exports are one importable family; private function is not
  importable across a cycle.
- Iterative SCC and topological ordering cover 10,000-unit chains and rings,
  with deterministic ordering independent of source-map insertion order.
- The file loader uses an iterative worklist and rejects symlink/hardlink
  aliases that would create multiple code-unit identities for one source.
- Duplicate import aliases (wildcard/wildcard, wildcard/named and host/Ores)
  are rejected before link or Java host lookup rather than using last-wins
  namespace shadowing. The runtime repeats this validation for direct eval.
- SCC ordering rejects edges targeting missing graph nodes rather than
  silently ignoring unavailable dependencies.
- Bare imports are checked against real configured source-root paths, blocking
  symlink escapes while accepting explicitly configured symlink roots.
- Named `import actor` selectors require `pub` on the target actor class,
  including in an otherwise valid circular import graph.

## Important remaining gaps

1. **Concurrency rendezvous:** The nonblocking startup gate is a conservative
   fail-closed policy, not async waiter/wakeup or automatic retries. Prove
   concurrent startup, cancellation and shutdown behavior under load before
   claiming contention-free initialization.
2. **SCC startup atomicity:** Units initialize individually on first use, not
   atomically as a group. A multi-unit cycle may have several READY members
   when a later member's init fails. The entry process aborts if its call
   fails, but group-wide rollback is not implemented.
3. **Export visibility:** Named actor selectors now check the target
   actor's existing visibility bit. Modules and type aliases still require a
   complete `pub` migration; TypeAliasDecl has no visibility bit. Track in
   Java issue #859.
4. **Native parity:** LLVM's frontend does not yet support multi-file import
   linking, and this PR does not claim native backend parity. Track in
   LLVM issue #341.
5. **Remaining stress and resolution:** Validate the new deep-graph,
   alias-collision, root-confinement and physical-file identity tests under CI;
   extend coverage to mixed Java bridges, module field evaluation, graph-changing
   incremental builds and scheduler-owned actor turns.

All implementation claims above refer to source changes on a **draft PR**.
GitHub Actions has not executed the branch's test suite successfully; no
runtime verification is claimed before CI passes.
