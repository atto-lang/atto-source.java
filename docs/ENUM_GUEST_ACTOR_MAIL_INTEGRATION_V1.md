# Compiler ↔ local actor-mail enum integration (draft)

**Status:** committed compiler/runtime integration with authored test suites.
**NOT_EXECUTED** on the exact branch head; neither guest correctness nor
cross-backend parity is certified.

## Source of truth and merge ordering

- Compiler base is Java enum expression draft #2847 (on #1598, on #1547).
- Runtime foundation is Java draft #2853 on `main`.
- This integration branch is stacked directly on #2847 and copies the canonical
  `OresSymbol.java` and `OresEnumSymbol.java` source blobs byte-for-byte
  from #2853 head `3f48ee610fe8b80585c9321be30229ba8ea51310`.
- When #2853 merges, **reconcile/rebase** this branch to use its canonical
  runtime files. Do not merge two separate definitions of these types or
  overwrite a newer mainline ActorRuntime. This branch is a stacked
  demonstration and requires semantic conflict review.

## Contract

- A fully nullary `define enum` has one compiler-built immutable
  `OresEnumSymbol.Declaration`, with defining-unit identity, nominal name,
  complete closed variant-name set, and selected case.
- Both explicit `Enum.Case()` and bare `Enum.Case` lower to
  `OresEnumSymbol` from the same declaration. Equality, exhaustive match,
  runtime type checks, return-type checks and async detachment recognize the
  canonical runtime value.
- The nominal enum symbol is NOT guest-visible `int` or `string` and does
  not use pointer identity for value comparison.
- Exactly-nullary closed enum types may appear inside PRIVATE/SHARED actor
  mailbox signatures and calls. This narrow type-checking exception does not
  authorize general same-file class/interface/alias references in actors.
- Mixed/payload-bearing enum declarations retain the existing nominal
  payload-value representation; they are **not** actor-sendable as the zero-copy
  symbol. Payload ownership/borrow proofs remain outstanding.
- Runtime actor-mail scalar paths carry nullary values directly, including
  nested container leaves, while preserving immutable-container copying and
  conservative full-declaration logical-byte quota admission.
- UNTRUSTED actor ingress rejects enum symbols until receiver-authenticated
  declaration export policy exists, even though the values are immutable.
- Cross-process / separate Wasm memory / VM transfers require explicit
  authenticated versioned symbolic encoding; this branch does not add it.

## Executable targets (authored, not run)

```sh
mvn -B -ntp -Dtest=NominalEnumActorMailGuestTest,EnumMatchArmSyntaxGuestTest,OresEnumSymbolActorMailTest -DfailIfNoTests=true test
```

The `.atto` positive cases send a typed `Light.Green` from a compiled
GraalVM guest into a PRIVATE `isoactor` and the default SHARED `actor`,
then consume the actor output and compare the enum value.
The negative cases require specific type/ownership diagnostics for foreign
nominal sends, untrusted enum ingress and payload-bearing enum mail. The
runtime JUnit suite is carried over from #2853 and exercises source-unit,
schema-version identity, large closed schemas, PRIVATE/SHARED host transport,
and nested untrusted refusal.

## Known evidence gaps

- No hosted JUnit/Surefire receipt, no compiler-generated guest trace, no
  verified zero-serialization profiler event or allocation measurement yet.
- A runner with `steps=[]` is runner admission failure, **NOT_EXECUTED**.
- The current Java shared JVM provides logical actor memory slices, not
  independently reclaimable physical actor heaps.
- No proof of JVM Native Image/AOT, LLVM, .NET, Wasm, Forge or JS parity.
- Independent review and exact-PR-head admission/negative receipts are required
  before removing draft status or merging.
