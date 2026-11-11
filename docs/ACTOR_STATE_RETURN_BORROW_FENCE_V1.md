# Actor-state returned borrow escape — compiler lifetime hardening V1

**Status: DRAFT, Java source compiler path and negative fixtures only.**
This slice stacks on [Java PR #2831](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2831)
and its earlier runtime/CONTROL/RW dependencies. It does not itself
implement compiler-to-runtime field-path RW lowering, native AOT parity or
source-export permission.

## Audited compiler defect

Before this slice, `OwnershipChecker.validateReturnedBorrow` permitted
a returned borrow whose ownership root was an **incoming borrowed
parameter**. An actor's implicit `self` is represented in the actor
method scope as an incoming exclusive borrowed parameter. Therefore
`return rt borrow self.count`, or a returned local alias of such a
borrow, could satisfy the generic incoming-parameter exception although
the actor's state allocation and authority end with the mailbox turn.

A borrowed alias of `self.box.value` or `self.items[0]` inherits the
same owner root. It cannot receive a lifetime that extends beyond the
actor's incarnation, nor bypass supervisor CONTROL scoped loans.

## Fail-closed compiler change

`OwnershipChecker.validateReturnedBorrow` now checks that a
`returned.borrowSource`, resolved through the alias root, is **not**
actor-confined `self` *before* allowing the incoming-parameter case.
A borrow rooted at actor `self` is rejected regardless of whether
its source was direct, a named immutable alias, nested class/record
projection, indexed collection member, or the whole `self` receiver.
This check runs in the same standard return analysis as existing
lexical borrow lifetime proofs.

The change does not prohibit copying a scalar value (`return
self.count`), nor returning a borrow of an ordinary incoming
function parameter that retains its caller-owned lifetime. No runtime
escape hatch, broad cast, or reflection exemption is introduced.

## Executable Java / .atto test contract

`ActorStateBorrowReturnFenceTest` loads seven canonical `.atto`
fixtures and uses the **actual** `Parser` and `OwnershipChecker`
for source-level rejection, plus `TypeChecker.check` for full
front-end rejection. The five unsafe inputs are:

1. Direct `return rt borrow self.count`.
2. `val borrow int alias = rt borrow self.count; return alias`.
3. Nested `return rt borrow self.box.value`.
4. Indexed `return rt borrow self.items[0]`.
5. `return rt borrow self` (entire mailbox capability).

Every negative test requires the **specific actor-state lifetime
diagnostic from OwnershipChecker itself**, not merely an unrelated
parser/type error. Two positive tests preserve safe value snapshots
and ordinary incoming borrowed-parameter forwarding.

The focused Java workflow now demands **27 RW + 32 supervisor CONTROL
+ 12 actor-state compiler/guest + 7 return-lifetime = 78 authored**
JUnit tests, with exact SHA/tree evidence, mandatory per-class
Surefire XML and no failures/errors/skips. Authored tests are **not
executed test evidence**. A runner admission failure with `steps=null`
must be recorded as `NOT_EXECUTED`; do not describe it as a failed
Java safety test or a passing one.

## Remaining P0 blockers

- Real Java compilation, executed JUnit and Graal guest evidence
  against an authorized exact source tree and assigned runner.
- Compiler-generated field-path/struct/index loan projections
  physically tied to per-actor memory, supervisor CONTROL, and STOP
  quiescence, not merely the original protected primitive proof slot.
- Removal of trusted package-local legacy supervisor `Future<Loan>`
  adapter after all consumers migrate to scoped CONTROL.
- Independent review, verified source-org CI/billing/runner settings,
  and cross-backend AOT (LLVM, .NET NativeAOT, Wasm, Forge) evidence.
- **Authored automated negative mutation gate** (not yet executed):
  after the 78 positive JUnit cases pass, the source-org workflow
  invokes `scripts/ci/actor_state_return_borrow_negative_mutation.py`.
  It removes the actor-self return guard in the **temporary authorized
  runner checkout**, reruns the direct-field escape JUnit method,
  requires one exact assertion failure (not an unrelated compilation
  error), restores pristine source and original passing Surefire XML
  in `finally`, and uploads separate mutation XML/JSON receipts.
  No private source crosses organization boundaries. Acceptance
  requires a real runner, nonzero executed steps, and the receipt.
  Subsequent work should extend the mutation matrix to all five
  negative projections and actual generated guest paths.

## Alternative organization testing is still authorization-gated

Source [issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
and conformance exposure incidents #22/#77 remain unresolved.
The `sonus-auris-test/clients-api-contract` source-free Node
canary [PR #31](https://github.com/sonus-auris-test/clients-api-contract/pull/31)
proved a separate organization runner executed real steps, **not**
authorization to copy this private Java compiler tree. No private
source is exported by this PR.

## Stacked field-path admission refinement (follow-up to this PR)

The subsequent actor-field admission slice rejects projected actor-rooted `rt borrow self.field` and `rt borrow self.items[i]` *before* their expressions reach `validateReturnedBorrow`. The negative returned-borrow fixture suite therefore accepts either a specific actor-field physical-loan admission error or the returned-borrow lifetime error **for projected field cases only**. The whole-`self` return fixture remains required to fail specifically at the returned-borrow lifetime fence.

Consequently the intentional negative mutation in this stacked follow-up targets `wholeActorSelfCannotEscapeViaBorrowedReturn` (not the earlier direct projected field test). The mutated deletion must allow an actor whole-self returned-borrow to pass, causing that one JUnit assertion to fail. Projected-path early rejections cannot mask a missing returned-borrow fence. The full combined gate is **27 + 32 + 12 + 7 + 8 = 86 authored Java tests**, **NOT_EXECUTED** until real source-org or independently authorized exact-source CI starts. No private compiler source was transferred.
