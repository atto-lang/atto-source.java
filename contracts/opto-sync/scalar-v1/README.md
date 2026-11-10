# Opto Sync exact-byte AttoLang scalar guest admission

The five .ores files are **byte-identical** to the candidate
`opto-sync/opto-sync-clients` PR #165 scalar fixture files.
Positive guest returns: `scalar-order=14`, `scalar-parens=42`,
`scalar-boundary=1`. `reject-overflow` and `reject-invalid` must
fail `--check` with a semantic diagnostic, not a JVM crash.

`OptoSyncScalarGuestTest` checks SHA-256 pins, runs the **real**
Java reference compiler/typechecker, executes positive programs with
`OresMain --mode=aot` and checks guest exit statuses. Its source
is built from the same hermetic scalar substrate as ClientScalarV1Test.
The workflow requires non-skipped JUnit XML and exact triggering SHA.

This does **not** certify Graal Native Image, actor/HTTP transport,
the `sync_admission.ores` structured module, durable database sync,
any other backend or a published SDK. Even if a workflow is marked failed,
absence of runner/steps means **NOT_EXECUTED**, not a compiler rejection.

## Corrected common-subset fixture contract

Sources now use `pub fnc main(): int` consistent with the existing
shared Java and LLVM compiler fixtures. The `reject-overflow` source is
specifically an *out-of-range signed i64 literal*, so its compile-time
rejection requirement does not accidentally assert that every checked
arithmetic overflow expression must be rejected at compile time.
Checked arithmetic traps require a distinct executed guest fixture.
All five source bytes must exactly match Opto Sync clients PR #165 and
companion LLVM PR #1639 at the **current** PR HEADs.
