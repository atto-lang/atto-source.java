# Actor-state typed projection borrowing — Java compiler preflight V1

**Status: stacked DRAFT, compile-time ownership checker only.**
Parent: [Java #2841](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2841),
based on #2831 → #2825 → #2820 → #2816 → #2758 → #2296.

## Scope and provenance

The Java `OwnershipChecker.borrowPlace` constructs an immutable,
statically typed sequence of field and indexed projection segments for
a borrowed place. The whole path continues to identify **one semantic
owner**, and borrowed field paths inherit the owner's lifetime. Each
field segment is resolved to a known, owned `Ast.TypeRef` using class,
generic substitution, or closed aggregate member types. Indexed
segments must be a known owned `Array<T>` or `List<T>` element
type. Unknown, borrowed, and inferred projection types fail closed.

For an **actor-owned** borrowed indexed projection, each index must
be a nonnegative integer literal or a direct immutable `const int`
binding with a proven integer literal; mutable locals, parameters,
side-effecting expressions, unknown selectors and negative indices
cannot mint a stable field-path borrow. This restriction is specific
to actor-owned indexed *borrows*; ordinary direct actor element
reads/writes retain existing type/runtime checks, and ordinary
non-actor borrow projections continue to support stable named
indices per the legacy model.

This is a deliberately conservative capability: all READ loans to
`self.position.x`, `self.position.y` or `self.values[0]` still
charge the entire actor `self` owner. Two READs may coexist, but
no WRITE may overlap *any* such READ, even to disjoint sibling
fields. Mutable indexed projections remain rejected until exact
path-exclusive loans have actual runtime memory-region backing.

The new immutable path structure is only internal to the compiler
pass. **It is not a runtime grant, shared-memory handle, or completed
compiler lowering.** No claim is made that the Java backend's actual
guest field/array access is already guarded by the supervisor
`ActorStateSupervisorLoans` arbiter. Path-level disjoint writes and
cross-actor sharing remain disabled pending end-to-end physical loan
and generation proofs.

## Mandatory .atto tests

`ActorStateTypedProjectionOwnershipTest` includes nine authored
tests using canonical `.atto` fixtures and real
`Parser.parse`/`TypeChecker.check`/`OwnershipChecker.check`:

1. Fixed literal actor-array index is accepted as a read borrow.
2. Direct immutable integer `const` index is accepted.
3. Multiple immutable borrows to different actor fields coexist.
4. Typed nested actor-class field borrow is accepted.
5. Ordinary non-actor dynamic named index borrow remains accepted.
6. Actor-owned mutable-local index borrow is rejected.
7. Actor-owned parameter index borrow is rejected.
8. Actor-owned negative literal index borrow is rejected.
9. A live actor read loan blocks a write even to a sibling field.

Negative tests require the actor-state-specific ownership error (or
existing borrow-exclusion error) from the compiler pass, *not* merely
an unrelated parser rejection.

The source-org exact-head workflow requires **27 RW arbiter + 32
supervisor CONTROL + 12 actor-state compiler/guest + 7 return-fence +
9 typed projections = 87 authored JUnit cases**. Real assigned runner,
executed steps, exact SHA/tree and five nonempty Surefire class
receipts with zero failures/errors/skips are mandatory.

Two **authored, not yet executed** exact-source negative mutations
run after the positive 87-test pass in an authorized temporary
checkout. The inherited return-borrow mutation must cause the
direct-field escape regression to fail for the intended assertion.
The new `scripts/ci/actor_state_projection_negative_mutation.py`
deletes the actor-owned constant-index restriction, reruns the
mutable-index JUnit method, and requires a targeted failing assertion
(one testcase, zero errors/skips) rather than a generic compiler
failure. Each driver restores pristine source and the original
passing Surefire XML in `finally`, and emits separate negative XML
and JSON receipts; the CI workflow fails if the guard cannot be
found exactly once or the mutation survives. Neither mutation is
production code and neither sends source to another organization.

## Security and proof holds

The source-org CI runner still fails at admission (`steps=null`);
none of the 87 tests is an executed passing receipt. The
[alternate-org source-free carrier #31](https://github.com/sonus-auris-test/clients-api-contract/pull/31)
proved another org can run independent Node tests only. Private
Java source mirroring remains forbidden pending independently
reviewed exact destination repo ID, source head/tree, protected
policy, and incident #22/#77 resumes under
[source security #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962).
No source export, cross-org checkout token, or merge is authorized by
this document.

P0 follow-ups: migrate actual actor field/array/projection accesses
to per-actor runtime regions; compiler-prove physical read/write
capability and exact-index alias paths; prevent suspension/STOP/restart
stale references; remove legacy free-standing supervisor host loans;
execute Maven/Graal/Native Image and negative mutations on an
authorized exact-head runner; obtain independent review and backend
parity.
