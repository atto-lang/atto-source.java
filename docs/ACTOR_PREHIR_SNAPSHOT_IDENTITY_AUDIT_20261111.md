# Pre-HIR snapshot-identity audit — November 11, 2026

**Status: DRAFT / no executed source compilation.**
This is a security hardening child of Java PR #2922, not a runtime unlock.

## Confirmed trust-boundary gap

The original ActorTypedMethodIR checker verified nominal actor/method keys,
source-unit IDs and signature metadata from an audit, but did not demand
that the actual `Ast.ExternalActorMethodDecl` and its declaring actor
`Ast.Decl` still belonged **by object identity** to the exact parsed
source snapshot handed to the type checker.

Although the current PackageNamespaceBuild passes one snapshot, accepting
a different parsed AST map with the same unit-ID keys could let an internal
future linker inadvertently combine a stale method witness with new code.

## Fix

- Both the referenced actor and external method declaration must be found
  in their expected ROOT_MODULE source units by reference identity (`==`),
  with namespace and source-unit consistency. Structurally equal ASTs parsed
  again do not satisfy an earlier witness.
- Failure is explicit `ACTOR_TYPED_IR_SOURCE_AST_IDENTITY_MISMATCH`.
- Literal **and statically computed** integer zero denominators, including
  `x / (1 - 1)` and `x % (2 * 0)`, are rejected with
  `ACTOR_TYPED_IR_DIVIDE_BY_ZERO`. Dynamically unknown denominators remain
  checked nodes; integer division is not executed or constant-folded.
- Statically provable signed 64-bit overflow in exact addition, subtraction,
  or multiplication is rejected with `ACTOR_TYPED_IR_INTEGER_OVERFLOW`.
  This is a conservative pre-HIR analysis, not runtime checked arithmetic.
- Synthetic ASTs with null/blank/overlong method parameter names fail with
  `ACTOR_TYPED_IR_PARAMETER_NAME_INVALID` rather than a null dereference.
- `ActorTypedMethodSnapshotIdentityTest` now has 6 authored tests: original
  exact-snapshot/stale actor or method cases plus malformed parameter names.
  `ActorTypedMethodIRTest` adds 4 tests for zero divisors, constant overflow,
  and checked dynamic divisors over its parent branch.
- Focused JDK25 CI retains the exact-head non-skipped Surefire requirement
  for both suites, and removes the optional GraalVM setup GitHub-token input.
  Checkout still uses only its own repository's read-only ephemeral token,
  never a cross-organization credential.
- The exact-head GraalVM JDK25 workflow demands a positive non-skipped
  Surefire XML report for both suites.

## CI and approved private carrier

- Source repo `ores-truffle-oreslang/oreslang-source.java` exact head of
  parent #2922: `72c8d861f3257fc52e15c1dc67416110b70b0157`,
  Git tree `d084359a7c37d92212c37f6dc3ced43b9e253d4c`.
- The private carrier `beamscale/bmscl-compiler` has immutable repo ID
  `1383937120` and stepful Java25 **source-free** run #42950858579,
  attempt 2, conclusion success. This establishes a working alternate
  runner and Java25 toolchain, **not** the source compiler tests.
- The checked-in `.github/private-ci-approved-repositories.txt` states
  that cross-org private-code destinations require independently reviewed
  approval. The carrier is described as proposed, not effective. NO
  private-source branch/tree/blob was mirrored in this stage.
- Before mirror, obtain the required reviewer/attestation for the exact
  destination ID, exact current source head/tree, pinned safe carrier
  workflow/test plan, no secrets/token checkout, and cleanup. Mirror ONLY
  after that approval. Verify every transferred Git blob SHA against the
  original and the complete tree SHA before claiming exact source parity.

## Remaining release gates

The source-org workflows have previously failed without assigned runner or
steps. Exact-head real Java compilation, all JUnit results, guest execution,
typed ownership/effect-sensitive HIR/MIR, actor critical state loans,
mailbox dispatch and five-backend parity remain UNVERIFIED/UNIMPLEMENTED.
These changes do not lift `DECLARED_ACTOR_CODEGEN_UNSUPPORTED` or
`DECLARED_ACTOR_MANIFEST_GATE_REQUIRED`.

## Revised-head transfer rule

Any independent approval must reference the **current PR #2933 exact head
and tree**, not the original commit included in the first request. A source
head change invalidates any old proposed mirror receipt. Verify immutable
source identity again immediately before approved transfer.
