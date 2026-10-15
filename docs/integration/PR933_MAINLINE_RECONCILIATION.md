# PR #933 — match/select arrow mainline reconciliation

This is an **unmerged, unvalidated integration staging branch**. It starts
from source `main` commit `4fae1bf29b19778387f7bcab8d306c734740a101`
and carries exact blobs for seven non-overlapping files from
`integration/match-select-arrow-mainline-20261014` at
`e9696988f2d7bac71d36b38dd3ea7232452af740`.

## Carried without overwriting newer mainline code

- `src/main/java/dev/oreslang/types/GenericCallArguments.java`
- `src/test/java/dev/oreslang/types/GenericCallArgumentsTest.java`
- `src/test/java/dev/oreslang/ArrowArmBoundaryTest.java`
- `src/test/java/dev/oreslang/MatchProjectionBindingKindsTest.java`
- `src/test/java/dev/oreslang/MatchSelectArrowDestructureContractTest.java`
- `src/test/java/dev/oreslang/MatchSelectArrowProjectionTest.java`
- `src/test/java/dev/oreslang/SelectDestructuringTest.java`

The new tests are not expected to pass or even necessarily compile until the
five conflicting production files are integrated; do not merge this staging
branch independently or treat it as passing test evidence.

## Five production files require semantic three-way merge

Both `main` and the PR modified:

1. `src/main/java/dev/oreslang/ast/Ast.java`
2. `src/main/java/dev/oreslang/parser/Parser.java`
3. `src/main/java/dev/oreslang/nodes/OresEvalRootNode.java`
4. `src/main/java/dev/oreslang/types/OwnershipChecker.java`
5. `src/main/java/dev/oreslang/types/TypeChecker.java`

**Critical AST hazard**: `main` now has `ChannelOperation.AWAIT` and typed
select arm metadata, whereas #933 adds select destructuring bindings, stronger
AST admission checks, and arrow-arm dispatch. Keep **both** the awaiting
operation and the destructuring metadata; preserving only one side will
silently drop language/runtime behavior. Every Java constructor and AST
visitor must be brought up to date.

Check the parser's static/non-blocking select paths, read vs await arm
admission, `match … over` and `do match` arrow syntax, and branch/control
flow boundaries. Match the ownership/type checker with the runtime's
projection binding modes, aliasing rules and cancellation behavior.
Never use a wholesale PR-head file overlay onto `main` for these five files.

## Verification gates after reconciliation

1. Compile and run the seven staged tests above, then the complete Maven suite
   under JDK 25.
2. Add explicit `await`-arm-with-typed-binding and destructured read-arm
   coexistence regressions; exercise fair/priority/select and failed binds.
3. Check actor single-carrier execution, completion deferral, cancellation
   cleanup and no sync-async callback ordering flips.
4. Build/test GraalVM native-image AOT/hybrid paths.
5. Authenticate the final immutable source commit/tree and run tests on a
   separately approved private carrier, **only after** source and carrier
   branch protection, independent security reviews, approved destination
   and exact source-tuple approval.
6. Keep #933 draft and unmerged until actual executed CI evidence and review.

This document records the staging state. It is not test evidence, permission
to export private source, or authorization to bypass protected CI controls.
