# V3 argument-shape proof integration

**Status: pure Java planner and compiler preflight, not guest execution.**

The predecessor branch (#1519) only preserves `T?` and `name?` syntax in
the AST and fails closed before interpreter execution. This branch adds
two explicit, inspectable intermediate checks.

## Source-free binding representation

`DefaultCallShapeV3.bind(formals, callItems)` is pure and works on a
previously selected, trusted **declaration's caller-visible value slots**.

- `Formal`: distinct name, intrinsic-Option proof bit (to be produced by
  the actual nominal resolver), explicit omittedness, and a closed
  immutable default witness.
- `Item`: one raw source item in **lexical source order**; either a
  provided expression or an omission marker, with optional static label.
- `Bound`: declaration/ABI slot, raw source item index, evaluated
  expression index (or -1), origin and materialization intent.
- `Plan`: immutable declaration-order slots and the strictly
  increasing raw-source order of provided expressions.

The model NEVER evaluates expressions, grants ownership/capabilities,
selects overloaded methods by type or labels, or authorizes any guest
execution. A supplied `self` in `self.foo(self, 5)` is still one of the
**two provided values**; the member receiver before the dot is separately
bound and excluded from the signature.

The binder rejects unknown/duplicate labels, trailing positional
arguments after named arguments, more items than slots, missing required
slots and omissions of required slots. Explicit omissions preserve a raw
item but never generate an expression. A plain `T name?` needs a
checked default, while an intrinsic `Option<T> name?` may synthesize
typed None. Immutable defaults become plain constants, Some(value) or
None according to verified formal type.

The planner also rejects malformed/fabricated origin maps, duplicate or
nonmonotonic evaluated raw indices, mismatched default-materialization
kinds, duplicate formal names and the synthetic value-formal `self`.

## Compiler preflight

`StagedArityOverlapV3.check` runs inside `TypeChecker.collect`
**before** the existing mandatory rejection of unlowered optional/default
metadata. It groups source-local overload declarations by module, owner,
STATIC/INSTANCE kind and name. A v3 declaration's accepted supplied
argument count is the closed interval [required count, all value slots].
Overlapping intervals fail rather than choose by labels, default
presence, runtime argument types or the implicit method receiver.

This preflight is **source-local only**. Inherited and imported signatures,
interface diamonds, actor/constructor ABI collisions, variadics,
versioned signature digests and nominal type resolution remain required
before feature activation. Older non-v3 same-arity duplicates continue
to be diagnosed by the existing compiler.

## Evidence in this iteration

Standalone Java 21 test of the **exact hashed**
`DefaultCallShapeV3.java` source compiled successfully and passed:
- 45,000 deterministic randomized raw call shapes;
- 1,553 admitted shapes checked for lossless source-order and ABI
  origin accounting; 11,710 successful assertions in that harness;
- explicit missing/default/Option/receiver/collision negative cases.

`StagedArityOverlapV3.java` was compiled against an isolated minimal
Ast surface shim and passed 6 focused overlap cases. JUnit test source
has also been added for the project build, but is not a verified JUnit
run (no matching JDK25 Maven repository checkout in the local runtime).

## CI/security

Source repo protected `main` still has an empty
`.github/private-ci-approved-repositories.txt` list. Security draft
#1326 proposes `sagitta-stack/sagitta-infra` but is not approved or
merged. Copying private source to an unrelated organization would breach
this repository's explicit review policy; no such export was performed.

## Promotion criteria

1. Resolve formal types by checked intrinsic symbol identity, not
   `TypeRef.name() == "Option"`; type-check and range-check every
   default literal, including alias/nested Option and overflow.
2. Bind the immutable plan to the selected callable declaration's
   owner, ABI version, signature fingerprint, and effects/borrow proof.
3. Reconcile and integrate the executable required named-call path
   in #1508; supplied expressions evaluate **once in lexical order**.
4. Prove inherited/imported overload intervals, constructors, actor
   mailbox ownership and dynamic dispatch never reselect at runtime.
5. Execute the same test matrix under Maven/JDK25, GraalVM Native
   Image and supported LLVM guest method paths with exact-head CI logs.


## Security/provenance hardening audit (2026-10-19, follow-up)

A concrete forged-plan gap was discovered: the old `Plan` record
constructor verified supplied expression-order indices but not a
**bijection over raw source tokens**. A manually constructed plan could
omit source-token index 0 and refer only to index 1, or assign the same
raw item to both an omission and a supplied expression.

The patched record now accounts for `provided expressions +
explicit omissions`; their raw-source indexes must be an exact
contiguous 0-based set, with each index present exactly once.
Unprovided formals have raw index -1; expressions keep strictly
increasing lexical source indices. The planner continues to be an
**untrusted model** until a selected-callable ABI/type/effects proof
attaches it to executable lowering.

New JUnit regression sources exercise missing, duplicate, reused
omission-token and valid mixed-provenance scenarios, and extend
randomized checks to cover all raw call items, not merely expressions.

Exact hashed model `40bd1c81b023b29d3b8853440d5c4dcb4a81fd21`
was locally compiled on JDK21 and passed:
- 45,000 deterministic baseline cases, 1,553 accepted, 11,710 checks;
- 100,000 adversarial randomized cases, 2,864 accepted, 8,288 checks;
- targeted forged-plan rejections and valid mixed omission scenarios.

The separate overlap shim/model `d08e493c2dc9a3c268856799e381cb9a9279fe39`
passed six isolated checks. These local runs do **not** execute the
project JUnit suites, the parser/typechecker as a complete binary,
JDK25/GraalVM Native Image or the LLVM backend.

On this date, no private source transfer to a test org is allowed by
protected `main`'s empty `.github/private-ci-approved-repositories.txt`
file. Security draft #1326 is not merged and has no recorded approvals.
Destination source-free canaries have also recently failed admission.
No external source mirror and no cross-org exact-tree CI test occurred.
