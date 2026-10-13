# Proposal: select result modes and over/among delimiters (13 October 2026)

**Status:** Proposed language contract; **not yet implemented** by the Java/GraalVM compiler, LLVM compiler, or formatter. The current Java parser treats static select as a statement, and dynamic select-from as a distinct expression. This proposal must not be represented as passing conformance until source parsing, type checking, execution, ownership, cancellation, and cross-backend tests pass.

## Decision

There are three *canonical* static select modes, each with three *equivalent* delimiters:

| Value/effect mode | Braces | End-delimited |
| --- | --- | --- |
| Blocking, value-producing | select { ... } | select over ... end / select among ... end |
| Blocking, procedural | do select { ... } | do select over ... end / do select among ... end |
| Nonblocking, value-producing | nb select { ... } | nb select over ... end / nb select among ... end |

An ellipsis in the matrix represents one or more select arms; a truly empty select is invalid. In the proposed model bare static select is *not* a procedural statement. Prior implementations that accepted bare select with ignored results must be migrated explicitly. A source compatibility period is possible, but cannot silently mix old and new expression semantics.

No canonical do nb select form is added. The current Java frontend accepts do nb select { ... } as a detached, no-result statement. That existing experimental spelling remains a **compatibility/deprecation decision**, not an implicit promise of same-function procedural returns: a callback executing after the caller returns cannot return from the caller. A separate named detached-handler API could be considered in the future. It must not silently reuse do select's lexical-return rules.

## Surface grammar

~~~ebnf
select_expression  = [ "nb" ] "select" [ select_policy ] select_body ;
select_statement   = "do" "select" [ select_policy ] select_body ;
select_body        = "{" select_arm { select_arm } [ select_default ] "}"
                   | ( "over" | "among" ) select_arm { select_arm }
                     [ select_default ] "end" ;
select_policy      = "first" | "fair" | "random" ;
select_arm         = ( "when" | "case" ) selectable_head "->" block ;
select_default     = "default" ( "->" | ":" ) block ;
selectable_head    = read_case | write_case | await_case
                   | selectable_next_case ;
~~~

The selectable head is typed and produces **one event**. Channel read, write, Future completion, Observable next-event and approved Selectable adapters are intended, but only handlers actually supported by the compiler/runtime can be enabled. Do not claim that arbitrary user-defined Selectable or Observable adapters are already compiled.

The grammar above is a target contract, not a drop-in replacement for current source grammar. Legacy arm spellings with a colon followed directly by a braced body can remain accepted during migration; the canonical spelling has a slim arrow (->) before the block.

Over and among are pure delimiter aliases: same AST, execution, type, cancellation, and error behavior. They are contextual identifiers only immediately after a static select header; do not globally reserve either word, and do not affect match ... over, ordinary variables/members, or dynamic select from cases.

The formatter shall emit **over ... end** for both input end-delimited spellings, to align with match ... over ... end. It must preserve brace-delimited outer shape unless the user has opted into delimiter conversion. Nested select/match/switch end tokens must be parsed structurally, never by a text regex.

Policy is orthogonal to delimiter and mode: select first over ... end, do select fair among ... end, and nb select random { ... } are well-formed headers. Dynamic select from cases retains its own API and must not be confused with this grammar.

## Values and control flow

1. select is a **blocking/suspending expression** of type R: exactly one winning arm runs and contributes its result. Blocking means logical suspension, not pinning a carrier/native thread.
2. do select is a **blocking/suspending statement** with no resulting expression value. Its arm bodies can have ordinary effects. A return in an arm exits the lexically containing function/method, just as do match and do switch should. This is valid only for a same-activation (non-detached) arm. The return must satisfy the containing function's type.
3. nb select is a **nonblocking expression** of type Future<R>. Register the selection and return a Future immediately; run the winning arm later on the actor's serialized lane (not on a parallel actor execution thread). The Future settles when the winning arm completes, not merely when its source becomes ready.
4. **No lexical return from value arms.** return select ... is permitted in the containing function; return inside a select or nb select value arm is not a way to return from that function. In particular, a deferred nb arm must never unwind an already returned caller.
5. The trailing unterminated expression of a value arm's block supplies R; arm-local preceding statements may end in semicolons. If the current language chooses a different explicit yield notation for match/switch expressions, use **one common rule** for all three constructs before enabling static select expressions. Do not treat ordinary trailing void statements as values.
6. All reachable winning arms, including default when present, must produce types assignable to a single statically known R. A missing value or incompatible arm result is an error; no implicit Any fallback. Never execute an unselected arm to infer its type.
7. If an arm's expression itself has type Future<X>, the immediate result is Future<X> for select and Future<Future<X>> for nb select, unless a separate, explicitly specified flatten operation is applied. Readiness await arms consume one settled event and expose its T payload, not another Future<T>.
8. A rejected, failed, cancelled or terminated selected Future/Observable/Channel event retains its declared error/cancellation semantics; no fake success value is produced to satisfy R.

~~~ores
const int received = select over
  when readch incoming: const int n -> {
    n + 1
  }
  when await ready: const int n -> {
    n
  }
  default -> {
    0
  }
end;

const Future[int] later = nb select among
  when readch incoming: const int n -> {
    n * 2
  }
  when await ready: const int n -> {
    n + 3
  }
end;

pub fnc process(Channel[int] commands): int {
  do select over
    when readch commands: const int command -> {
      if command == 0 then
        return -1;
      fi
      stdio.println(command);
    }
    default -> {
      stdio.println("nothing ready");
    }
  end
  return 1;
}
~~~

As with current static select, a default branch means that if no selectable is ready at the atomic decision point, the default wins rather than installing waiters. A blocking select without default may suspend until readiness, cancellation or another terminal condition. A nonblocking select is *asynchronous*, not the same as an immediate try select probe. The separate try select / select from cases APIs require their own typed migration plan; this proposal does not redefine them.

## Concurrency, liveness and ownership

- Registration/arbitration must atomically claim **one** winning event; losing channel reads must not consume data, losing writes must not send, losing Future/Observable listeners must be unregistered. Handle readiness-cancellation races without duplicate resolution.
- Existing FAIR rotation (default), FIRST lexical priority and explicitly RANDOM policy apply unchanged for all three delimiters; an urgent mailbox/control lane must not be starved by data select overload.
- Deferred nb arms carry owned captures to the completion continuation using the existing move/copy/borrow rules. Captured ordinary loans and mutable guards must not escape across registration. Only one native thread may run actor code at a time, despite logically overlapping select operations.
- A blocking select is a suspension boundary. Enforce the same loan checks as await, especially on critical actor state loans. do select does not bypass suspension safety.
- Cancellation/STOP unregisters pending selection cases and releases reservations; nb Future completion is one-shot on success, failure or cancellation. An actor cannot turn a discarded winner's completion into a successful result.
- Reject send/write arms that cannot produce the required result under the chosen value-mode contract. Avoid silently discarding emitted payload errors when type checking branch results.
- An unobserved nb select Future is a potential unhandled failure; require explicit handling or an intentional discard policy at the language/effect level, not implicit fire-and-forget.
- Do not allow blocking do select return semantics to leak into detached continuations, callback jobs or spawned actors.

## Parser/compiler lowering

Normalize outer delimiter without erasing result mode:

~~~text
SelectForm {
  waitMode: BLOCKING | NONBLOCKING,
  resultMode: VALUE | PROCEDURAL,
  delimiter: BRACED | END_DELIMITED,    // source span / formatter only
  policy: FAIR | PRIORITY | RANDOM,
  arms: [SelectArm],
  default: SelectArm?
}
~~~

There should be one shared arm parser and one winner-selection runtime; syntax aliases must not create multiple arbitration implementations.

- Parse a bare select in expression context, including assignment, return, nested call operands and parentheses. The old parser only parses a static braced select in statement context and routes expression select to dynamic select from; this requires explicit work, not a grammar-only change.
- Parse do select in statement context, not the expression parser. Enforce a compile-time error on use in an assignment and on mismatched end/brace closure.
- Distinguish ResultArmBlock from procedural StmtBlock to avoid consuming value expressions as ordinary ExprStmt. Lower blocks into a typed join/phi (or equivalent continuation-resume result slot), preserving source-order effects.
- Compile nb select to Future<R> whose winner body is resumed on the owning actor lane. Do not complete it with the readiness token before evaluating the winning body. Check exception/effect and cancellation propagation.
- Existing DynamicSelectExpr(select from cases) and static SelectStmt must be migrated deliberately; keep SelectSet semantics and avoid changing them incidentally.

## Acceptance tests before claiming support

Positive parse/type/runtime tests for all **nine** spellings (three result modes × three delimiters), policy combinations, nested end-delimited matches/selects, immediate readiness vs future readiness, and mixed Channel/Future/Selectable arms. Test nested typed destructuring and typed-arrow binding where those features are compiled.

Negative tests: empty arm set; mixed/missing terminators; duplicate defaults; value arms without values; mismatched branch types; do select used as a value; return inside nb/value arm; detached captures of forbidden loans; unsupported selectable operations; cancellation vs wakeup races; malformed await/readch precedence; duplicate firing; actor STOP while armed; attempts to write into full user mailboxes that consume reserved control headroom.

Formatting tests: among -> over canonicalization, idempotence, comments/strings containing 'over', contextual identifiers named among/over, policy spelling, and fully nested arm braces. Differential parity gates: Java/GraalVM interpreter; Java AOT/hybrid; LLVM compiled guest; cross-backend conformance. No merge based only on parser acceptance or documentation.

## Current implementation gap (as audited on main)

- Java Parser.parseStaticSelectStatement() currently consumes only LBRACE and produces SelectStmt, which does not carry an expression result. The expression parser routes select to parseDynamicSelect(), which requires FROM.
- Java SelectArm currently stores List<Stmt> for its body. It lacks a typed value-arm result, and current SelectStmt.explicitDo is a marker rather than a complete VALUE/PROCEDURAL semantic split.
- Current docs/channels-select-and-actor-cancellation.md explicitly describes bare static select as a compatibility statement and do nb select as detached void. That existing behavior conflicts with this proposed new canonical contract and needs a deliberate breaking migration.
- LLVM and oreslang-format must be audited independently. Syntax acceptance in one frontend is not cross-backend support.

**Suggested staging:** (1) end-delimiter lexical/parser and AST normal form with failing value-mode tests, (2) typed result arms and blocking select, (3) nb Future<R> continuation and cancellation hardening, (4) formatter, LLVM and formal/conformance parity. Keep this PR draft until real compiler/runtime changes and exact-source evidence exist.
