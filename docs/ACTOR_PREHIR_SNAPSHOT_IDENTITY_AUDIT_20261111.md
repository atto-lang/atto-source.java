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
- Literal int denominators of 0 under `/` or `%` are rejected with
  `ACTOR_TYPED_IR_DIVIDE_BY_ZERO`. Dynamic divisors still produce checked
  typed expression nodes requiring future trap-aware runtime lowering.
- `ActorTypedMethodSnapshotIdentityTest` (4 new methods) probes exact-snapshot
  success, reparsed same-byte actor/method replacement, and a changed method
  with unchanged source-unit ID. `ActorTypedMethodIRTest` adds 2 cases for
  literal zero divisors and dynamic checked divisor behavior.
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
