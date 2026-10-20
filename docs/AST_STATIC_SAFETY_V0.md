# Experimental AST static-safety warnings (Java/Graal)

Status: prototype and opt-in. Not a security scanner, proof of bounded resources, taint solver, static-facts v0 exporter, or exhaustive analysis. It does not change normal build admission.

Call OresCompiler.analyzeStaticSafety(source) to type/ownership check Oreslang source and then receive immutable diagnostic records. Parser-only analysis is for fixture coverage and cannot be used for security admission.

## Experimental CLI

```sh
oreslang-compiler --static-safety src/main.ores
oreslang-compiler --static-safety --fail-on=warning src/main.ores
oreslang-compiler --static-safety --fail-on=never src/main.ores
```

The CLI returns **JSON on stdout** with schema `ores-static-safety/v0`,
`assurance: advisory_unverified`, `producer_authenticated: false`, and
`proof_complete: false`. It is not the `ores-static-facts/v0` schema,
nor a verified LLVM/JVM certificate. The scanner runs the standard Java
type and ownership admission before producing advisory findings and
**does not execute guest code**. `--fail-on=warning` uses status 1 when any
finding exists; by default (and with `--fail-on=never`) findings do not
change exit status. Invalid invocations return usage status 2.
`--static-safety` rejects runtime, permission, build and strict-isolate
options rather than allowing their names to imply enforced proof.
The renderer rejects >10,000 findings, >16,384 characters per field, and
>8,388,608 UTF-16 code units of JSON text. It intentionally does not invent source spans or SARIF
locations; these require compiler-authenticated typed IR source metadata.


## Current rule IDs

- ORES-TERM-002: syntactically unconditional loops without an apparent reachable exit; potential nontermination.
- ORES-TERM-003: syntactically unconditional loops with only conditional or otherwise unverified exits; their termination is not proven. A first-statement trivial `break` or literal `return` is exempted as a narrow syntactic special case, not a general ranking proof.
- ORES-AMP-001: possible recursive actor creation via spawn ActorType(...) and a statically recognized receive/on_message handler.
- ORES-AMP-002: multiple syntactic actor-spawn sites occur in a recursive component; potential multiplication, **not** a feasible-path fanout count or proof that all branches execute.
- ORES-AMP-004: callable SCCs under uniquely resolved same-module direct calls.
- ORES-AMP-005: actor creation inside source loops without demonstrated total bound.
- ORES-SEC-010: candidate host execution/FFI call requires independent authority and data-flow validation; not a proven vulnerability.
- ORES-SEC-011: a non-literal expression is passed to a syntactically recognized privileged host call. This is a taint-analysis **review obligation**, not a claim that input is untrusted, exploitable, or unsanitized. Constant folding and true source-to-sink provenance are not implemented.
- ORES-ANALYSIS-001: unresolved target, nominal overload, or handler. Never claim completeness.

## Limitations

AST nodes currently lack adequate source locations and proof metadata for production diagnostics. Direct call resolution omits many method/dynamic calls; task, mailbox, supervisor and arbitrary callback propagation are not covered. Spawn is not equivalent to delivery to receive: the actor edges represent a potential event path, not synchronous execution. No ranking function, actor admission resource bound, taint flow, sanitizer proof, runtime capability certificate, or full LLVM parity is implemented.

Warnings are deterministically sorted and traverse AST constructs without source text heuristics or reflection. The node traversal has a finite budget, but deeply nested source is not yet fully stack-safe. SCC witness lists are component members, not verified directed-cycle paths.

Full source semantics must be checked by the Java/Graal compiler, and real native host effects still require trusted runtime/OS enforcement. A parser-only fixture is not guest compilation/execution evidence.

Unit tests reside at src/test/java/dev/oreslang/compiler/StaticSafetyAnalyzerTest.java. Run in the real project Maven JUnit suite, record exact commit and executed CI steps. Zero-step GitHub Actions failures are not successful tests.

Shared reference contract: https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/106

## Callback timing / Zalgo warnings

The opt-in AST pass also runs `CallbackOrderingAnalyzer`:

- `ORES-CB-002`: a callback captured by an unknown-timing API may emit
  an event **before** a matching `on` / `once` / `addListener` registration.
- The analyzer handles direct lambdas, named local lambda bindings,
  straightforward local emitter aliases, constant or dynamic event names,
  and conservative branch/path merging.
- It does **not** infer that an API is microtask-deferred simply from
  an async function signature or a Future return type. Unknown callback
  timing permits inline invocation and cross-worker concurrency.
- Absence of this warning is NOT a proof that registration is synchronous,
  listener lifetimes are safe, that the emitter cannot fire independently,
  or that async code cannot race the registration.
- A listener passed directly to an `on` registration is not treated
  as immediately invoked by that act of subscription.
- `ORES-CB-006`: a subscriber appears textually before callback
  publication, but its synchronous installation, failure handling, and lease
  lifetime have not been proven. A source-level `on()` method name is not
  an effect certification.
- A wildcard event name cannot establish a definite installed subscription.
- `once` subscriptions are conservatively treated as potentially consumed
  by an explicit matching `emit` or an unknown-timing callback containing a
  matching emission. A durable `on` at the same syntactic event remains
  independently registered, but is still merely lexical evidence (`CB-006`).
  This is deliberately conservative: it does not count one-shot listener
  multiplicities or verify a specific event library's delivery semantics.

- The lexical pass now invalidates prior syntactic registration evidence for
  `off`, `removeListener` / `remove_listener`, and `removeAllListeners` /
  `remove_all_listeners`. Unknown event names conservatively invalidate every
  registered event on the same recognized emitter. This models possible
  detachment only: it does not prove listener identity, successful removal,
  registration completion, or subscription retention through actor turns.
- Complex aliasing, dynamic/method callbacks, other library-specific
  detachment APIs, host FFI, multi-actor scheduling, hot reload, async
  registration, and interprocedural effect summaries still require compiler
  integration.

### Ownership / borrow checker plan

Introduce compiler-verified callback timing effects on callable parameters
(`inline_only`, `deferred_after_turn`, `concurrent`, `mixed`, `unknown`).
A closure that captures an emitter and is passed to a potentially inline/
concurrent API is an escape point. The ownership checker should verify
exclusive/reentrant access and required **active registration leases**
across that escape point, as well as release, cancellation, and hot-reload
paths. Capture of a unique mutable emitter across a concurrent callback must
respect actor ownership and `rt share` rules. This typestate/barrier check
is a **future compiler feature**, not implemented by the current lexical pass.

Canonical cross-backend design:
https://github.com/ores-truffle-oreslang/oreslang-common-source/blob/feat/static-analysis-contract-v0/docs/CALLBACK_TIMING_ZALGO_ANALYSIS_V0.md


### Source-derived callback may-effects (experimental)

`CallbackParameterEffects.infer(program)` examines local callback
parameters in parsed Oreslang ASTs, recording positive evidence for direct
inline invocation, possible capture/forwarding/escape, and potential
suspension/deferred use. It is also exposed through the ownership-admitted
`OresCompiler.analyzeCallbackEffects(source)` API.

These summaries enrich the `ORES-CB-002` witness text. They are **not**
trusted timing guarantees: methods, imported/dynamic/FFI calls, nominal
overloads without resolved identity, async functions, `await`, callback
retention, aliases, hot reload and unknown call targets must not be
classified as guaranteed inline-only or safe after-turn. Mixed/unknown
effects will eventually join across the resolved generic call graph.

This is a staging interface for integration with `OwnershipChecker`.
The existing ownership checker already performs loan/capture safety checks,
but it does not yet enforce listener-installation typestate or carry
compiler-authenticated callback effect proofs. Do not enable an AOT or
security admission gate based on the presence of a source-derived summary.
