# AttoLang actor receiver contract v1: portable source tests

Status: **draft / UNEXECUTED**. Java frontend only until exact-head
compiled guest receipts exist. This suite extends
`contracts/actor-module-v1`; it does not replace that corpus.

`pub mtd name(Cart cart)(Args...): Return;` is a **contract requirement**
for an existing, mailbox-dispatched method declared inside actor `Cart`.
The first parenthesized receiver is not an ordinary value argument, and
this signature creates no callable function, state loan, or ActorContext.
A module contract must declare `actor Cart;` (directly or through a
non-generic parent). Only non-generic, synchronous, non-structural method
requirements with no checked effects are currently in scope.

| Fixture | Expected compiler / guest result |
| --- | --- |
| accept_existing_actor_method.atto | Frontend accept; guest main => 42 only when actually executed |
| reject_missing_method.atto | Reject: missing required receiver method |
| reject_private_method.atto | Reject: public requirement cannot use private implementation |
| reject_wrong_return.atto | Reject: incompatible return |
| reject_unrequired_receiver.atto | Reject: receiver actor not declared in contract hierarchy |

IMPORTANT: A backend that simply rejects the positive fixture due to
unimplemented syntax has **not** achieved feature parity. Record
per-backend support and exact-head executed receipts, not source-only
claims. External `define mtd` bodies and standalone actor aliases are
not yet enabled. A signature cannot grant actor-state capability.

Required report fields: backend, compiler SHA/hash, source PR head/tree,
fixture blob SHA, runner admission and executed steps, compiler
exit/diagnostic, guest exit/result, and current parity verdict.
