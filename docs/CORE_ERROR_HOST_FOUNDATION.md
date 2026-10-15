# Java host-only core error foundation (integration slice)

This PR lifts the trusted **99-entry** `std/error` catalog, Java nominal metadata, a bounded diagnostic codec, and focused unit tests onto the latest `main` without replacing the actor runtime, compiler parser, typechecker, or evaluator that have evolved independently.

## Explicit boundaries

- These are JVM host-only catalog descriptors, *not* guest-visible Oreslang class instances.
- Only runtime-owned descriptor identity can be used for protected nominal metadata; guest-supplied numeric `code()` or class names are not authority.
- `ActorError` is an abstract nominal category that directly declares `ActorApiError`, inherited by all actor-specific descendants; retry hints confer no authorization to retry operations.
- Compact supervisor diagnostics omit guest messages and frames; the codec does not authorize a CONTROL notice.
- Local diagnostic trace frames are bounded to 64 entries before copying; mutable JVM ArrayList inputs require actor-exclusive/immutable ownership.
- The package manifest is data parity only. Source-level `std/error` imports, typed throw/catch and LLVM source lowering are explicitly outside this slice and still require the stacked language integration.

## Verification

Run on the exact PR head:

```sh
mvn -B -ntp -Dtest=CoreErrorDescriptorsTest test
mvn -B -ntp verify
```

CI must execute steps and publish passing results. Zero-step failed jobs must **not** be recorded as evidence of tests passing.

Related: Java #811 (guest source error hierarchy; conflicts with current main), Java #936 (metadata hardening stacked on #811), LLVM #375 (host native parity).
