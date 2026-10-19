# Scalar Task admission and execution audit — 2026-10-19

**State:** DRAFT security/semantic integration hardening on Java PR #1484,
stacked on #1479 → #1475 → #1466 → #1411. Do not merge or remove the
broader Task execution gates without exact-head executable validation.

## Canonical syntax and normal form

- `task { ... }` is the preferred Task-producing expression.
- `do task { ... }` is the preferred explicit detached statement (currently
  compile-rejected by general execution policy).
- Legacy `nb { ... }`, `nb task { ... }` and `do nb { ... }` remain parser
  compatibility aliases; they construct exactly the same `Ast.TaskExpr` /
  `Ast.TaskBlockStmt` and **do not** introduce a second scheduling tier.
- Nonblocking `nb readch`, `nb writech`, `nb select` and `nb cb writech`
  retain their separate meaning.
- Only direct `await task { return <literal-or-local-scalar>; }` is currently
  eligible for guest execution in this draft. No general `Task<T>` binding,
  nested/escaping handle, detached body or user callback is enabled.

## Threat model / authorization chain

| Stage | Verified-by-code boundary | Still forbidden |
|---|---|---|
| Parser | Canonical and legacy spellings normalize to one AST | Bare discarded Task expression |
| Ownership checker | Capture traversal and suspension borrow constraints | Borrowed / move-only / actor-private Task values |
| Type checker | Trusted public, ordinary, zero-argument root `fnc main`, direct await, scalar return and a lexical local name only | Foreign/module-global names, escaped Task handles, nested callbacks, other functions, async/generator/trapped/nlex entrypoints |
| Source continuation | `ScalarTaskPlan` enforces single return expression; parent evaluates captured scalar once | Guest calls, FFI, arbitrary AST or Env transferred to worker |
| Entrypoint provenance | The VM `executeMain` mints a per-dispatch launch mark; only the matching `SourceTask` retains an immutable entrypoint proof | Merely being named `main`, being called via exports, or using root scheduler |
| Runtime scalar frame | Exact `Long`, `Double`, `Boolean`, `String` class whitelist; independent snapshot; maximum 16 captures and 1,048,576 UTF-16 units per string | Native handles, references, actor objects, arrays, null, nested Task/Future, unbounded string copies |
| Scheduler | Existing `OresScheduler.Await` resumes heap-owned continuation and does not park a physical carrier | Blocking guest `AsyncRuntime.await`, arbitrary Task closures |
| Lifetime | Context-owned scope retains child until physical completion; terminal worker failure releases through `getDone()` | Claiming cancellation itself is physical quiescence |

## Corrected integration weaknesses

1. Name-only or resolved scalar type was previously sufficient to obtain the
   scalar Task capture shortcut. Now the compiler requires the binding to
   originate from the lexical root main (never its module/global parent).
2. The root-main flag could leak into syntactically nested/deferred analysis.
   Normal lambdas, expected-type lambdas, mutex callbacks, isolated callback
   blocks, select arms and class method analyses explicitly clear it and
   restore their previous state on all exits.
3. Root scheduler identity alone was not sufficient to establish root
   entrypoint provenance. The runtime now requires an immutable launch proof
   captured by a SourceTask built during actual `executeMain`; exported
   calls to a function named `main` cannot forge this fact.
4. The static and runtime entrypoint checks agree on exact public ordinary
   `fnc main` requirements, and reject untrusted, actor, async, generator,
   trapped, nonlexical and generic entrypoints for the initial slice.
5. String transfer is limited before scheduling. Regression tests check both
   string size boundaries and legacy syntax spellings.
6. Runtime documentation no longer implies root cancellation physically joins
   or automatically cancels its independent child. Context teardown does
   retain and drain the scalar frame.

## Verification status

**Actually executed:** Exact GitHub production `ScalarTaskPlan.java` blob
(`80fe383cc95d4ce7a154c7af55e6d9546e8c8dac`) hash-matched locally;
Java 21 `javac --release 21 -Xlint:all -Werror` compiled it against
minimal test-only AST interfaces, and the isolated shape probe passed 8/8.
This does **NOT** compile the whole production compiler, parser or runtime.

**Not executed/verified:** Maven/JUnit, complete TypeChecker/OwnershipChecker
compilation, Graal polyglot `.ores` guest execution, scheduler cancellation/
failure stress on the exact PR SHA, native image, LLVM, and ECS/region
permission lowering. A passing syntax/static check is not a full guest pass.
The source organization's Actions jobs are queued or blocked, not green.

Suggested first runnable exact-head source-repo gate:

```sh
mvn -B -ntp -Dores.runtime.carriers=java \
    -Dsurefire.failIfNoSpecifiedTests=true \
    -Dtest=TaskSpellingSyntaxTest,TaskBlockSyntaxTest,GuestScalarTaskAwaitTest,TaskSpawnSendAdmissionTest,TaskTraitAdmissionHardeningTest,OresScalarTaskFrameTest,OresTaskScopeFrameTest,OresTaskPartitionQuiescenceTest,ScopedTaskTypeBridgeTest \
    test
```

Then run the complete Java CI, security/concurrency stress and GraalVM/native
matrix. Only authorized private-source runners may execute those tests; do
not mirror private repository contents to another organization without
independent security authorization.

## Remaining release blockers

- Parent-to-child cancellation linkage and scoped child ownership for all
  nontrivial Tasks. A context-owned pure scalar child may outlive its canceled
  root temporarily but must be joined before VM teardown; do not generalize
  that arrangement to mutable data, arenas or FFI.
- Authentication of canonical runtime intrinsic identities for future
  `Channel`, `Arc`, `RwLock`, `Task` and other Send/Sync capabilities.
  Nominal names alone cannot grant these traits.
- Compiler-minted allocation-domain generation/ownership permits and
  checked runtime pin acquisition/release on **physical** task exit, not
  cancellation/result settlement; guest `InvertedArray`/ECS parallel writers
  remain disabled.
- Java/Graal full guest AOT and native LLVM zero-GC AOT semantics parity.

**No merge until executable checks run and blockers for the enabled slice
are resolved.**
