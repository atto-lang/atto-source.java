# Oreslang formal concurrency checking and source analyzer (experimental)

This folder is an initial, executable **bounded abstract-model checker** plus a
**lexical userland linter**. It is intentionally NOT described as a verified
compiler, a verified implementation, a universal deadlock detector, or a proof
of JVM/OS actor isolation.

## Run

```sh
python3 -m unittest discover -s tools/tests -v
python3 tools/formal/check_actor_model.py --actors 2 --messages 2 --workers 2 --capacity 1
python3 tools/oreslang_lint.py --format json examples/
python3 tools/oreslang_lint.py --pedantic examples/
```

The formal tool explores *every reachable abstract state* at a finite model size
until fixpoint. If `--max-states` is exhausted, it fails rather than reporting
success. Counterexamples include predecessor action traces. The checked
invariants are exclusive actor leases, message uniqueness and accounting,
bounded ordinary mail, continuation consistency, and termination cleanup. The
model permits arbitrary interleavings of sends, I/O completions, actor ends,
worker acquisition/release, receives and continuation resumes. It models
continuation **priority** but not actual wall-clock scheduling fairness,
multi-frame nested awaits, independent continuation/control capacity, select
registration/arm cancellation, or unsafe host code.

The selected two-actor/two-message/two-worker/capacity-one configuration
exhaustively reaches 949 states and 4,364 transitions on the current prototype.
This is a *bounded model result*, not a theorem about unbounded populations or
an assertion that the Java runtime refines the model.

## Source-lint findings

| Code | Default | Interpretation |
| --- | --- | --- |
| ORES101 | warning | `defer` inside a loop, review cleanup registration/lifetime |
| ORES102 | warning | `await` apparently inside a non-async function |
| ORES103 | opt-in info | `select` lacks syntactic default/timeout; blocking may be intentional |
| ORES104 | opt-in info | `match` is not syntactically demonstrated exhaustive |

Comments and string literals are excluded by lexical tokenization. Warning
rules are advisory; `--strict` opts into a failing exit code. They must not be
used as a security gate or described as sound/exhaustive static analysis.
In particular, a missing `catch`/`recover` is **not** automatically a bug if
an effect is handled by a typed result, a caller, or a deliberate policy.
A `defer` in a loop might be legal and intentional depending on scope
semantics. As the language syntax evolves, the lexer is replaceable.

## Required follow-up: soundness, refinement and runtime integration

1. **Identify actual semantic transitions** in Java/LLVM runtimes. Define a
   versioned TypeSpec/JSON-Schema event protocol for actor lease, mail admission,
   delivery, continuation wake, select arm registration/winner/loser cancellation,
   close/end, resource finalization, supervision/restart and hot-load generations.
   Reuse the existing `contracts/v1/semantics/traces/` validator where possible.
   Emit trace data from real runtime boundaries, not only mock schedulers.
2. **Refinement mapping:** project observed runtime events onto abstract state,
   reject illegal transitions with the shortest actionable counterexample;
   include initial state and generation IDs. Reconcile omitted internal steps as
   stuttering and record the exact artifact/source/tree SHA tested.
3. **TLA+/TLC or Apalache:** maintain reviewed, version-pinned executable
   specifications for user mailbox + independent continuation quota, select
   atomicity and lost-wakeup freedom, cancellation, suspend/resume, lease
   exclusivity, supervisor restarts, generation-fenced hot-loading, shared-cell
   synchronization, and actor-group teardown. Express fairness assumptions
   explicitly before making any liveness claims.
4. **Compiler-typed analyzer:** replace lexical rules with a typed AST/CFG +
   effect graph, interprocedural summaries and fixed-point abstract interpretation.
   Track may-throw/may-fail, cancellation, blocking, ownership/borrows, actor
   affinity, resource lifetime and protocol-state transitions across `await`,
   `try/catch/finally`, `recover`, `defer`, channels and `select`.
   Require recovery only where there is a genuinely unhandled effect. Emit
   stable diagnostics with code locations and SARIF/JSON outputs.
5. **Pattern proofs:** decide exhaustiveness only from a closed discriminated
   union/enum/tuple type and guards, including unreachable and overlapping arms.
   Open/dynamic types must be conservatively reported as unproven, not as
   exhaustively checked. For deadlock claims, distinguish local cycle proofs from
   whole-program/environment assumptions.
6. **Regression proof harness:** positive/negative fixtures per rule, seeded
   mutation tests that intentionally violate an invariant, runtime trace
   projection tests, randomized schedule testing, and cross-backend conformance
   on the same TypeSpec-generated contracts.

## Review/merge gate

Do not treat a successful abstract-model run as a runtime proof. Gate merge on
executed Python tests, separately executed GraalVM JDK 25 tests if compiler code
is touched, and a reviewed account of soundness boundaries. Source-org Actions
that fail before a runner starts are **not** failed tests; verify an exact Git
tree in a funded test org when necessary without sharing private credentials.
