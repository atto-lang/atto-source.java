# AttoLang `.atto` actor-state container and compiler ownership gate V1

**Status: stacked DRAFT implementation; Java parser and ownership-checker
convergence, not general runtime RW field-lowering or five-backend parity.**

Stack order:
[Java #2296](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2296)
→ [#2758](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2758)
→ [#2816](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2816)
→ [#2820](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2820)
→ [#2825](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2825)
→ this PR.

## Canonical actor state syntax

The Java parser now recognizes one private `state { ... }` container per
actor:

```atto
define actor Counter as
  state {
    let int count;
  }
  on_start(): void {
    self.count = 0;
    return;
  }
  receive(ActorMail<String> mail): void {
    self.count = self.count + 1;
    self.send(self.count);
    self.end();
    return;
  }
end
```

This is syntactic grouping only, using the existing
`Ast.ClassDecl.fields()` representation and actor-ownership checks.
It does NOT manufacture a second state object, a public actor field,
a `Shared<T>` alias, or a supervisor-accessible pointer. Ordinary
actor fields remain accepted for legacy `.ores` compatibility. New
`.atto` fixtures use the canonical grouped form.

The state block disallows modifiers/annotations on the group, a second
block, public/static actor fields, nested declarations and methods.
**Mixing a grouped state block with ungrouped legacy actor fields is now
rejected in either order**: when a group is present it must account for
every actor-owned storage slot, so later borrow lowering cannot enforce
the group and silently miss a bare field. Actors with no group retain
legacy bare-field compatibility.
Private `let`/`val` and existing final actor fields use the existing
parsing/type semantics; no new dynamic reflection is required (AOT
compatibility).

## Compiler-level ownership hardening

`OwnershipChecker` previously permitted explicit `rt take` to enter
generic move logic on actor-owned field projections. For a Copy scalar,
that could appear legal despite having no projection-level actor-slot
move, tombstone or generation proof. It now **rejects `rt take` from
any expression rooted at actor `self`**, including scalar Copy fields,
and suggests an ordinary immutable snapshot or properly scoped
supervisor loan instead.

This guard is a compiler error before backend lowering. Existing
actor-field checks also keep mutable projections fail-closed until
exclusive path-level loan proofs exist, and prevent moving
non-Copy state fields out of the actor turn.

The predecessor's legacy `ActorStateSupervisorLoans.Loan` type is
also now package-private, matching its package-private raw `redeem`
adapter. An existing JUnit visibility regression asserts both. This
removes that free-standing host handle from the external Java public
surface, **but does not yet remove** the trusted runtime-package legacy
adapter; remaining tests using it must migrate before the implementation
can claim complete physical-unwind-only release.

The Java backend still stores real guest fields in
`ActorOwnedGuestState`. This PR does **not** yet lower arbitrary field
projections and supervisors to `ActorStateSupervisorLoans`. The prior
primitive `protectedScalar` runtime proof slot is *separate* from
general guest fields. It is unsafe to claim the field-level RW
contract is fully integrated until compiler-generated projection
capabilities and physical-unwind receipts are implemented.

## Exact-head compiler + guest test gate

The new `ActorStateContainerOwnershipCompilerTest` loads real
`.atto` files from `src/test/resources/ownership/actor-state/`,
runs `Parser.parse` + `TypeChecker.check` (which invokes the
ownership pass), and executes the accepted actor program using
`org.graalvm.polyglot.Context` with `allowAllAccess(false)`.

Twelve authored tests:

1. State fields flatten into private actor `Ast.FieldDecl`s.
2. Valid grouped state passes the compiler ownership/type checks.
3. A real Graal guest increments actor-owned state, emits output,
   stops, and returns the expected `1`.
4. Two compiled actors each have distinct mutable state despite sharing immutable code (output `1|1`).
5. Copy scalar `rt take self.count` fails in the ownership checker.
6. Public state fields fail at parse time.
7. A second state container fails at parse time.
8. Methods and nested code inside state fail at parse time.
9. Bare actor fields preceding a grouped state block are rejected.
10. Bare actor fields following a grouped state block are rejected.
11. A public/modifier-decorated state group is rejected.
12. Legacy actors using bare fields without a state group remain valid.

The exact-head `.github/workflows/actor-state-rw-ownership.yml`
checks **27 existing RW arbiter + 32 existing supervisor CONTROL +
12 new compiler/guest tests = 71 authored tests**. It requires
nonempty real Surefire XML reports for all three test classes with
zero failures, errors or skips. A job without assigned runner,
nonzero executed steps and exact-head SHA/tree receipt is **NOT_EXECUTED**.
No test has been certified passing merely because it was authored.

## Remaining critical work

- Run all 71 tests on an authorized Java 21 CI runner and fix any
  real compile/runtime failures; preserve negative mutation evidence.
- Replace any remaining free-standing raw supervisor host
  `Future<Loan>` adapter with the lexical scoped CONTROL API, then
  remove its guest-reachable surface.
- Bind compiler-proven actor `state { ... }` field paths
  (nested structs/arrays/ADT projections) to per-actor actual runtime
  region/loan authority rather than only a proof scalar.
- Authenticate generated `define supervisor` CONTROL and no-escape
  guest borrows through the host runtime, including suspension, STOP
  and restart generation fencing, and use independent reviewed AOT tests.
- Require independently approved review and parity receipts for
  Java/GraalVM, LLVM, .NET NativeAOT, Wasm and Forge/Cranelift.

**Private source export hold:** conformance security incidents #22/#77
and Java runner issue #963 still prohibit unreviewed private source
mirrors to another GitHub organization, regardless of Actions minutes.
