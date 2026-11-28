# Oreslang concurrency formal checking and analyzer (Rust prototype)

All executable verification and lint implementation is in the Rust crate
`tools/oreslang-verify/`. No Python dependency or Python source is needed.

## Run

```bash
cargo test --manifest-path tools/oreslang-verify/Cargo.toml --all-targets
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- model --actors 2 --messages 2 --workers 2 --capacity 1
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --format json examples/
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --pedantic examples/
```

The Rust checker enumerates all reachable states **within explicitly finite bounds**,
and fails rather than declaring success on reaching the configured state limit.
Checks include actor lease exclusivity, mailbox bounds, message uniqueness and
bookkeeping, continuation status and termination cleanup. A counterexample
reports the transition path. This is **not yet** a proof that the Java/LLVM
implementations refine this model, nor a liveness/fairness proof.

The Rust userland linter tokenizes Oreslang excluding comments and string
literals. It currently reports: ORES101 loop-declared defer, ORES102 await in
apparently synchronous function; pedantic-only ORES103 select without a
syntactic timeout/default and ORES104 match without a syntactic wildcard.
Warnings are advisory, not a typed exhaustive analysis or security gate.
Missing catch/recover can be intentional and must be checked using effect
information before it can be called an actual defect.

## Next integration and proof obligations

1. Generate TypeSpec/JSON-schema contracts for actor lease, mailbox, select
   registration/winner/cancel, continuation wake, close, restart and hot-load
   generation events. Check **real** Java and LLVM traces against the Rust
   model with an explicit refinement mapping.
2. Model independent continuation/control/user queues and bounded select
   registration, cancellation, notification and lost-wakeup freedom.
3. Add reviewable TLA+/TLC specifications with explicit fairness assumptions
   for stronger temporal properties; Rust remains the executable tooling.
4. Feed typed AST/CFG, ownership, type, and effect data into Rust static
   analysis: exception recovery, resource closure, await suspension,
   lock/channel deadlocks, exhaustiveness and unreachable pattern arms.
5. Add SARIF, mutation tests, actual runtime trace fixtures and cross-backend
   exact-head verification; do not merge based solely on model checks.

Source Actions attempts with zero executed steps do not prove a defect. When
needed, mirror exact Git blobs and subtree into a funded test organization's
temporary branch without sharing private repository tokens.

## Second increment: Rust runtime trace and typed IR

`cargo run -- trace path/to/actor-trace-v1.json` validates the repository's
existing `contracts/v1/semantics/traces/trace.schema.json` event vocabulary
for real `spawn`, `admit`, `send`, `receive`, exclusive-resource
acquire/release and `terminate` events. It checks schema version, sequence
continuity, actor liveness, uniqueness/ownership, and completed-trace closure.
The monitor does **not** create or trust any runtime trace; only authentic
runtime instrumentation can provide implementation evidence. This Rust reader
does not claim to replace full JSON Schema Draft 2020-12 validation.

`cargo run -- select-model --arms 3` exhaustively enumerates a second,
explicitly bounded abstract model for select-arm registration, offering a
winner, close, cancellation, deregistration of losing arms, and at-most-once
notification. The abstract transition `offer_commit` is assumed atomic. It
must be refined against the actual JVM/LLVM select implementation before this
can justify claims about production lost-wakeup or double-delivery behavior.

`cargo run -- ir typed-program.json [--strict]` analyzes **versioned,
compiler-supplied** CFG facts using `schema_version=0.1.0`. The importer
validates IDs/edges, reachability, closed finite pattern domains, guards, and
unreachable/overlapping match arms. It flags a defer on a reachable graph
cycle, mutable borrow live across a suspension, unhandled may-throw effects,
blocking operations on an untrusted actor, and select operations without
losing-arm cancellation. These are conditional on a correct, trusted
compiler-generated export: accepting a hand-written JSON file does **not**
prove the corresponding source correct.

Typed IR data contract:
```json
{
  "schema_version": "0.1.0",
  "path": "main.ores",
  "functions": [{
    "name": "main",
    "actor_kind": "untrusted",
    "allows_error": false,
    "entry": 0,
    "nodes": [
      {"id":0,"line":1,"column":1,"op":"entry","successors":[1]},
      {"id":1,"line":2,"column":1,"op":"await","successors":[2],
       "borrowed_mut_across_suspend": false},
      {"id":2,"line":3,"column":1,"op":"return","successors":[]}
    ],
    "matches": [{
      "line":2,"column":1,
      "closed_variants":["Some","None"],
      "arms":[{"variants":["Some"]},{"variants":["None"]}]
    }]
  }]
}
```

Important: there is **not yet** a verified compiler-owned export producing
this IR from `OresCompiler.analyze`. Until that bridge is implemented and
tested on real programs, keep the existing lexical `lint` for userland files
and treat `ir` as a separately tested analyzer protocol. The v1 actor trace
contract does not yet carry the detailed lease, continuation, select arm, and
generation events needed for full runtime refinement; a distinct versioned
extension and backend instrumentation are required.

## Audit hardening (October 9, 2026)

The actor transition relation now **refuses to dispatch ordinary mail while a
guest continuation is waiting for I/O completion**. A carrier may be acquired
only for ready continuations or for eligible runnable mail, and the invariant
rejects a suspended actor holding a carrier without a ready continuation.
Because invalid interleavings were removed, the reference two-actor model now
has 707 reachable states rather than the earlier 949.

The Rust lexical linter now exposes `tokens_checked` and `lint_checked`.
Unclosed strings, nested block comments, and mismatched/unterminated brackets
are explicit input errors. The CLI exits nonzero on malformed source rather
than producing an empty issue list; the compatibility `lint` API emits
`ORES000` for incomplete analysis. This lexer still does **not** parse source
semantics, backtick interpolation or the complete grammar.

The compiler-supplied typed-IR validator now recognizes mutable borrows
across `await`, `select`, `readch`, `writech`, and `yield`, and can flag
unhandled `may_throw` on any typed IR node. A terminal `return`, `throw` or
`exit` node cannot declare successors. A non-suspending op cannot claim a
borrow-across-suspend fact. Cycle membership now uses iterative, linear-time
SCC analysis rather than quadratic graph walks. All analysis continues to
depend on correct compiler-generated metadata.

Trace checks reject explicit JSON `null` for typed optional string fields,
and trace and typed-IR API payloads are bounded at 16 MiB. CLI source walks
reject symlink inputs, directories deeper than 64, more than 20,000 matching
files, files larger than 4 MiB, and zero-file scans. Path-based checks are not
a race-free filesystem sandbox: secure inspection of hostile mutable trees
requires descriptor-relative, no-follow file opening.

Regression tests cover malformed sources, stale-carrier acquisition,
mailbox/continuation ordering, closed CFG terminals, select borrowing,
exception effects, cyclic vs acyclic graphs, null trace fields, oversized
JSON, and CLI symlink/size failures. The CI workflow now requires Cargo tests,
strict Clippy, bounded model exploration, trace and typed-IR fixtures. A
successful model run remains only a bounded **abstract-state** result; no
production runtime refinement or liveness proof is claimed.

## Core verification and userland CLI hardening

The bounded actor model rejects termination with a live carrier lease.
External shutdown is modeled only after quiescence; in-flight cancellation
and production runtime trace refinement remain open obligations.

```bash
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- trace-core tools/oreslang-verify/fixtures/core-trace-v2-example.json
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --format sarif --pedantic examples/
cargo run --manifest-path tools/oreslang-verify/Cargo.toml -- lint --format json examples/
```

The proposed core-trace v2 input requires schema_version=2.0.0, complete,
capacity (1–65536), and zero-indexed contiguous events. The Rust monitor
checks actor and carrier exclusivity, FIFO delivery, bounded mailboxes,
send authorization by an active guest lease, await/ready/resume ordering,
duplicate I/O completion, termination, and cleanup. Events: spawn, admit,
send, acquire, receive, release, suspend, io_complete, resume, discard,
terminate. Event-specific worker, target and message_id fields are checked.
This is a proposed trace contract, NOT evidence that JVM/LLVM backends emit
or reliably source these events. A trusted trace exporter and independent
mailbox capacity attestation are necessary before production proof claims.

The lint command now outputs SARIF 2.1.0 in addition to JSON/text and
refuses explicit non-.ores files and empty source scans. Nested functions
do not inherit surrounding loop-defer warnings. Typed IR now checks
duplicate control-flow edges and misplaced select cancellation facts; the
JSON Schema includes select/readch/writech/yield operation vocabulary.
Neither the lexical linter nor hand-authored typed IR proves source safety.

The funded Actions mirror validates exact Git blob hashes, Cargo unit and
integration tests, Clippy with -D warnings, model exploration, both trace
formats, typed-IR fixtures, malformed source rejection, and SARIF output.
