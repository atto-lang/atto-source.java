# Sendable + MessagePack v2 + nested supervisor integration evidence (2026-10-16)

**Status: DRAFT / INTEGRATION NOT YET CERTIFIED.** This document is not a
substitute for executing the complete Java, GraalVM Native Image or LLVM suites.

## Source and provenance

- Parent source commit (MessagePack PR #645): `275abb94c90b3766d1005dfadfdaaec80768f2bb`
- Supervisor PR #631 source: `efc463a38c4601bf8656cca5a5ddc459c248b119`
- Sendable PR #1060 source: `b25a5b7921b1fafb9574d29ffc62574f2a80e791`
- First integrated commit: `57f887dbc1f29d8af56e88f3f9b911713e47fae2`
- First integrated Git tree: `524a28848250a2ef10f658fc07e237f3790418d8`
- Overlay: 15 added/modified Git paths; unchanged MessagePack paths retain their exact base blob IDs.
- `ActorRuntime`: 14 of 15 supervisor diff hunks applied with exact match to
  the MessagePack runtime; the nested spawn-group admission and rollback block
  was ported explicitly from the supervisor branch.
- `TypeChecker`: both Sendable compiler diff hunks applied to the MessagePack
  source; preserves existing pipeline/nominal message admission rules.

## Tests actually executed

Locally under Java 21, the standalone supervisor CONTROL smoke suite completed:

```text
PASS: 22 assertions; exact control+guard blobs, ActorRuntime identities stubbed
PASS: 26 assertions; exact supervisor process, CONTROL guard and lane, runtime identities stubbed
PASS: 4006 consumer-exclusive CONTROL assertions; 1000 actor/host races
PASS: 900 terminal CONTROL retention assertions (50 repetitions)
```

Total **4,954 passing assertions**. These use isolated Java runtime-identity
stubs and **do not execute the integrated `ActorRuntime`**. The three
production CONTROL class blobs were SHA-checked against:
- `SupervisorFailureControl.java`: `774867b7eae0323d86283d11e3289d805803cae5`
- `SupervisorDispatchGuard.java`: `d65624e62a444e931e4cd7c15a292b918f0ef556`
- `SupervisorProcess.java`: `a9861b5b6fc7b6b29ea8eb6a1029da9616e6124b`

Source GitHub Actions run 38977517142 (`fast-ci`) concluded failure
with **zero executed job steps**. It did not compile or reject these sources.
A separate private funded hosted runner was verified on
`sagitta-stack/sagitta-infra` in Actions run 38946243870 (5 steps passed),
but that was source-free. Do not treat it as an exact-tree Oreslang build.
The connected GitHub source-transfer operation into a different organization
was previously blocked by safety checks. Do not bypass those restrictions or
move private source into a public repository.

## Outstanding release and merge gates

1. Compile and test this exact integration tree using a permitted private
   complete-tree source transfer and GraalVM JDK 25 `mvn verify`.
2. Execute nested actor-as-supervisor A -> B -> C failure/cleanup tests, including
   the untrusted actor group authority-denial test.
3. Execute actual source-level, typed actor MessagePack variants and Sendable
   rejection tests together (host codec unit tests alone are insufficient).
4. Stress child-group admission, release, cancellation, restart and CONTROL
   queue ownership, with lossless resource-credit accounting.
5. Run native-image AOT/hybrid and LLVM wire/ownership conformance.
6. Reconcile all stacked prerequisites and rebase/adapt against current `main`
   before proposing an upstream merge. Do not mark unimplemented platforms
   as conformant.
