# Actor sealed state and read/write critical admission v1

Status (2026-10-12): **contract plus incremental lexer/negative-check and native
logical ticket substrate; not Java/LLVM source-codegen parity**.

## Source contract

Actor `state { ... }` is a **single compiler-owned declaration**, not an
ordinary field. Never materialize it as a guest-visible `self.state`, reflective
field, dynamic property, native pointer, inherited field, or mutable alias.
Only the sealed, non-overridable `self.handle_state` intrinsic may mint an
ephemeral state capability. A class/actor cannot supply its own implementation
of `handle_state`. Type-check/validate all field accesses, including through
aliases, closures, getters, cast/dynamic/reflection and FFI.

Proposed canonical source (positive only after complete backend lowering):

```ores
define actor Foo as
    state { v: int = 0; }

    pub increment(): int using state.read, state.write {
        return self.handle_state(critical-write |s| -> {
            s.v = s.v + 1;
            return s.v;
        });
    }

    pub get(): int using state.read {
        return self.handle_state(critical-read |s| -> s.v);
    }

    receive(Message m): void {
        // self.state.v = 3;    // error: self.state is not in scope
        // val i = self.state.v; // error: self.state is not in scope
    }
end
```

The `handle_state` argument must be a lexically inline callback taking
**exactly one** state-access parameter. Unmarked callbacks remain forbidden:
`self.handle_state(|s| -> ...)` is not a synonym for a read, since mutation
and effect inference have to agree. Preexisting `critical |s| -> ...` remains
the exclusive mode for backward compatibility. `critical-write` is its
explicit new spelling; `critical-read` is a read-only mode. A read-only
callback must not call an unknown/mutable function, return/store/borrow its
state capability, or retain a state-backed alias through suspension. A write
callback may read and mutate; `using` must cover both actual effects.
A direct assignment without reading its prior value requires state.write only.

`critical` on file/static/instance callables retains existing exclusive
logical-gate behavior. Proposed `critical-read` on a callable uses the shared
read gate and `critical-write` uses the exclusive writer gate, scoped to the
same instance/code-unit as the existing critical member gate. The parser may
eventually accept modifiers before or after `fnc`; **do not** accept them as
no-op modifiers before compiled metadata and scheduler handling exist.

## Keyword tokenization

The hyphen is ASCII U+002D (`-`, HYPHEN-MINUS).
Recognize `critical-read`, `critical-write`, `transform-pipe`, `nb-select`
only as exact contiguous reserved spellings. Ordinary `a-b` and `a - b`
remain subtraction; `a->b` remains the arrow. No generalized hyphenated
identifiers, and do not combine tokens across spaces/comments. Unknown forms
fail closed; `transform-pipe` and `nb-select` are reserved without asserting
runtime syntax support.

## Actor execution, snapshots and fairness

Oreslang's **at most one physical guest executor per actor** invariant is
unchanged. Multiple logical read permits must **not** cause the same actor's
guest code to execute on multiple carriers. Shared reads can run in parallel
only on verified immutable, generation-pinned snapshots outside that actor's
single-flight guest turn; ordinary in-actor callbacks still execute serially.
Without snapshot lowering, `critical-read` must fail closed rather than
claim parallelism. A writer is exclusive against readers and writers, never
held as an OS mutex across a guest callback/await.

Admission is FIFO with consecutive readers in one cohort, bounded queue,
cancellation-aware tickets, no barging after a queued writer, and a scheduler
CONTROL wake path. Tokens are gate scoped, monotonic and generation-checked;
retirement revokes tickets. No implicit read-to-write lock upgrade (release
and retry with an application-level version check). Same-continuation nested
callbacks must fence the outer loan and use trusted reentrancy handling; a
new ticket must not deadlock its own parent. A yielding callback retires its
state loan before yielding, reacquires a fresh ticket/loan after resumption,
and does **not** promise callback-wide atomicity across await.

## Release gates

- Reject direct read, write, address-taking, aliasing, reflection, state
  shadowing or dynamic lookup of `self.state`; reject overriding handle_state.
- Reject non-inline callback, wrong arity, escaping loan, understated effect
  row, writes in read mode, unknown transitive FFI, and live loan at await.
- Verify token writer fairness, reader cohort, cancel/handoff race, stale/
  duplicate/foreign tickets, shutdown and actor exclusivity.
- Lower runtime-owned typed state for Java/Graal and LLVM before accepting
  positive source. Run identical positive/negative source fixtures on both
  backends, with sanitizer and parallel stress tests. Existing lexer/ABI
  tests alone cannot satisfy this release gate.

See `CRITICAL_HANDLE_STATE_USING_PARITY.md` and
`CRITICAL_CALLABLE_SEMANTICS.md` in the LLVM repository for prior contracts.


## Hardening audit (2026-10-12)

The first implementation found and addressed the following boundary gaps:

1. Java parsing denies direct `self.state` within actor classes and rejects
   actor-defined `handle_state` methods (including `static fnc` declarations).
   Java's type checker additionally rejects `.state` access based on a
   receiver's nominal actor type; aliases cannot bypass the literal syntax
   check. These checks do not imply the positive state schema is lowered.
2. The native RW admission substrate caps **active reader tickets at 1024**
   and queued tickets at **8192**. Writers are FIFO-ordered; arriving readers
   cannot overtake an already queued writer. Modes outside Read/Write reject
   before any ticket is minted.
3. C++ admission transfers allocated linked-list nodes from waiting to active
   ownership, without further allocation during handoff. Before changing
   ownership the release method reserves all needed wake-list space, so
   an allocation failure cannot strand a newly admitted ticket. Linear
   active-ticket lookup is the deliberate safety tradeoff pending profiling.
4. Retirement returns **all outstanding owner/waiter tokens** for terminal
   cancellation instead of silently dropping waiting continuations. Runtime
   scheduler integration MUST consume this batch and signal cancellations
   outside the gate metadata mutex. This adapter is not integrated yet.
5. Multithread cancellation/handoff, queue ordering, retirement and active
   reader bounds have targeted C++ tests. CI jobs currently fail before steps
   execute; those tests remain unverified on a build runner.

**Must remain draft:** no source-level critical-read/write effect or loan
lowering, no trusted immutable snapshot proof, no scheduler ticket binding
to guest continuations, no user-visible actor-state read concurrency, and
no demonstrated Java/LLVM end-to-end parity. A successful native gate test
is not evidence that guest actor execution is race-safe. Explicit read-to-write
upgrades are forbidden pending versioned retry semantics.


## Java semantic boundary hardening (2026-10-12)

The Java type checker now repeats the actor-state sealing rule independently
of token parsing: effective inherited actor field layouts cannot contain a
field named `state`; local or inherited actor instance methods named
`handle_state` are rejected; local static actor methods with that name are
also rejected. This protects against synthetic/expanded AST declarations and
imported ancestor shapes, not only direct source text. Regression fixtures
construct forged actor declarations in the AST to exercise these checks.

Java Parser/TypeChecker/JUnit **full-source compilation and runtime tests
are not yet verified** because the source repository's own Actions jobs did
not execute. The exact Java Lexer/Token keyword smoke tests previously
passed on a funded runner; do not conflate that with semantic enforcement
or source-level `state {}` lowering.

For security, future exact-source copies of this private code MUST target
private repositories only. See private LLVM security issue #256 regarding
the earlier public CI mirror history and administrator purge follow-up.


## Security re-audit: inherited static intrinsic shadowing

The Java semantic pass checks every method kind in the actor's entire
inheritance graph, including **static methods in imported linked parent ASTs**.
A syntactically valid source import cannot smuggle a synthetic
`handle_state` override into the actor runtime namespace.

Private exact-source Java 21 parser, ownership and type-checker compilation,
including this imported-parent negative regression, passed in
https://github.com/agent-pontifex/agent-pontifex-daemon/actions/runs/38317005320.
These checks do not enable positive `state { ... }` lowering or substitute
for whole-application GraalVM and actor scheduler execution tests.


## First-class sealed state frontend AST — 2026-10-12

The Java parser now recognizes `state { value: int = 0 }` in an actor
and stores its fields in `Ast.ClassDecl.actorState()`, a distinct
`Ast.ActorStateDecl`. It is NOT a member of `ClassDecl.fields()` and
cannot be treated as ordinary object/actor field storage, inherited fields
or generated JSON bindings. Existing AST constructors remain source-compatible.

State fields currently require explicit types and initializers. The parser
rejects duplicate state blocks, duplicate/reserved state names, public state
declarations and collisions with ordinary actor members. Semicolons are
optional at newline/closing brace as in ordinary statements.

**Fail-closed compiler boundary:** `TypeChecker` rejects any actor
state declaration *before annotation expansion*, until the compiler supports
type/effect/loan checking and fully structured `handle_state` lowering.
`AnnotationExpander` retains the new state metadata rather than dropping
it through old ClassDecl constructors. Linked classes cannot import a
private runtime-owned actor state AST. Lexer recognition and parser AST
acceptance are not executable semantics.

Exact-source private Java 21 compiler/parser/semantic regression CI passed:
https://github.com/agent-pontifex/agent-pontifex-daemon/actions/runs/38321947352.
The run is a focused core compilation and smoke suite, NOT full Maven,
GraalVM execution, source actor codegen, or Java/LLVM behavioral parity.


## Actual Java JUnit sealed-state boundary verification (2026-10-12)

The `ActorStateBoundaryKeywordTest` JUnit 5 class now explicitly covers
state declarations separated by newlines, semicolons, and forbidden
same-line adjacency. Compiler analysis continues to reject recognized
sealed state prior to AST expansion or execution until effect-checked
`handle_state` lowering is complete.

[Private Java 21 exact-source CI #38323331038](https://github.com/agent-pontifex/agent-pontifex-daemon/actions/runs/38323331038)
compiled the tested parser/type-checker sources and ran the actual JUnit
Platform/Jupiter suite: **15 tests passed, 0 failed, 0 skipped**.
The same private CI run passed the native gate/continuation GCC and
Clang sanitizer checks. This still does not cover the entire Java Maven
suite or GraalVM runtime actor codegen.


## Typed actor reader/writer callbacks: first compiler slice (2026-10-12)

The parser now recognizes `self.handle_state(critical-read |s| -> ...)`
and `self.handle_state(critical-write |s| -> ...)` **inside an actor**
as `Ast.ActorStateAccessExpr(StateAccessMode, LambdaExpr)`, not a normal
`CallExpr` or reifiable function value. The AST constructor and parser
require one lexical inferred state binding, no explicit referent
mutability, and no reserved `self`/`state` binding. Malformed/multiple
callbacks, other argument forms, and unsupported critical-declaration
spellings remain rejected.

`ActorStateLoanShapeChecker` admits only a deliberately restricted
copy-scalar shape: reads of callback-owned state fields, pure arithmetic,
and in write blocks, assignments to the callback's own field followed by
a final scalar return. It rejects read mutations, escaped `s`,
`await`, nested calls, nested state access, unknown fields, captured
outer names and non-Copy field shapes. This is a preliminary syntactic/
effect-shape proof, not complete ownership, mutation-alias, exhaustive
control-flow or exception/cancellation semantics.

`TypeChecker` and `OwnershipChecker` **still reject all
`ActorStateAccessExpr` execution** until compiler-generated state loans
and scheduler-authorized cleanup are implemented. The original
`state{}` pre-expansion gate remains in force, even for callbacks that
pass the shape checker.

Private exact-source [Java 21 JUnit CI #38330707512](https://github.com/agent-pontifex/agent-pontifex-daemon/actions/runs/38330707512)
passed 20/20 targeted JUnit tests plus independent native gate/sanitizer
checks. No end-to-end `Counter` binary is yet supported.

## Re-audit: typed scalar effects and exceptional arithmetic (2026-10-12)

The first Java `ActorStateLoanShapeChecker.analyze` now derives a field-level
`LoanEffects(reads, writes)` result and a conservative internal expression
type. The *verified shape subset* is **int/bool only**, not all potentially
Copy numeric types. The checker rejects incompatible state initializers,
wrongly typed writer assignments, mixed operand types, unsupported unary
operations and division/remainder. These restrictions are intentional:
otherwise a syntactic "pure" expression might silently require conversions
or raise division errors without a proven state-loan unwind path.

The shape checker can still accept int arithmetic that may overflow under
a future checked-integer contract; **it does not prove a callback cannot
throw/raise/panic**. This field-effect summary MUST NOT be substituted for
an exception-effect analysis, a method `using state.read/write` row proof,
borrow/alias analysis, or an executable scheduler-admission authorization.

New negative parser/JUnit coverage was committed for incompatible scalar
types, bool/int confusion and unchecked division, plus positive bool reads.
The previous 23/23 JUnit green run predates these edits. A new private
runner attempt completed without executing any steps because Actions capacity
was unavailable. Treat these additions as **committed but unverified** until
an exact-source Java 21 run executes the new JUnit suite and AOT semantics.
`TypeChecker` and `OwnershipChecker` continue to fail closed.
