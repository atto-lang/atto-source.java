# Guest scalar Task v0: one inline, awaited, owned-copy value

**2026-10-19 — DRAFT stacked on #1479, #1475, #1466 and #1411.**

**Canonical spelling:** `task { ... }` creates a Task; `await task { ... }` immediately joins it. `nb { ... }` and `nb task { ... }` remain parser-level compatibility aliases and lower to the same AST. Bare nonblocking channel/select `nb` is unaffected.

## Enabled slice

Only a root `pub fnc main(): void` in the synthetic ROOT_MODULE can contain:

```ores
pub fnc main(): void {
    let int x = 41;
    let int result = await task { return x; };
    stdio.stdout.write(result);
    return;
}
```

The Task expression MUST be the direct operand of `await`. It must contain
exactly one `return` of a **literal** or **name**. The statically resolved
return type must be `int`, `bool`, `float` or `string`. The compiler's
ordinary ownership pass still executes first and rejects invalid captures.
The type checker additionally denies all other callable/actor contexts. No
first-class `Task<T>` values or fire-and-continue Tasks are enabled.

The evaluator uses the SAME `ScalarTaskPlan` AST validator as the type
checker, reads the scalar once in the parent's lexical context and hands the
value to `OresContext.startCompilerScalarAwait`. The context enforces
trusted, non-actor root-scheduler execution; `OresScalarTaskFrame` enforces
an exact host scalar class whitelist, creates an independent snapshot and
runs on the existing scheduler. The root source continuation is suspended
via the established `OresScheduler.Await` mechanism (no carrier parking);
the context-owned scope keeps the child through physical completion.

**This is deliberately more restrictive than a general source Task.** It
does not implement region borrowing, move-only aggregate captures,
nested/escaping Task handles, detached work, ordinary function bodies,
untrusted quota inheritance, supervisor/actor ownership, or LLVM parity.

## Boundaries and residual audit work

- No `Env`, `Ast` closure, custom runtime object, FFI handle, `Task` handle
  or guest pointer is handed to the scheduler worker. The immutable
  scalar snapshot happens before child admission.
- Root-only eligibility is compile-time AND runtime checked; a privileged
  host bridge cannot bypass the copied-scalar exact-value restriction.
- Context ownership is sufficient only for the immediate-await scalar
  child, which cannot retain parent resources. A general Task child requires
  a first-class scoped parent-child owner, admission token and generation pin
  before lifting the `current()` nested-Task guard in
  `startCompilerTaskFrame`.
- Future result settlement is not physical completion. Code/region release
  needs `OresTask.getDone()`/host exit callbacks and robust cancellation,
  failure and cleanup tests. This v0 frame executes runtime-internal scalar
  operations only, not reloadable guest code or borrowed memory.
- The compiler still needs a single authenticated source-to-resolved
  Send/Sync policy for all future nominal and wrapper types; not implied
  by this scalar whitelist.
- The new `GuestScalarTaskAwaitTest` has both real polyglot guest execution
  tests and negative compiler cases; CI adds it to `concurrency-contract`.
  **Until exact-branch Maven/JUnit runs this is unverified full integration.**
- Keep the new stacked PR draft and do not merge until authorized
  source-repository CI runs real Maven/JUnit test steps. Never transfer private
  source to another organization without security approval.

## Further progression

1. Exact-source JDK21+ Maven/JUnit and GraalVM guest Task tests, then real
   cancellation/teardown stress on the verified stack. Fix any negative
   compiler regressions and validate the full language suite.
2. Give ordinary `task { ... }` a compiler-minted region and parent-scope
   token, with a typed heap-owned capture frame and owned return ABI.
3. Allow a first-class `Task<T>` result and `await Task<T>` only once
   scope membership, cancellation and physical-exit cleanup are proved.
4. Bind `TaskAccessVerifier` tickets to private runtime permits/pins;
   implement disjoint mutable writers with generation fencing before ECS
   and InvertedArray lowering.
5. Prove LLVM exact guest parity with zero-GC native arena frames and
   deterministic destruction before announcing two-backend support.
