# AttoLang nominal enum symbols in local actor mail (V1)

Status: **implementation authored; NOT_EXECUTED on the exact PR head**.
Scope: Java/GraalVM interpreter runtime, shared-JVM actor mail. No LLVM/.NET/Wasm/Forge parity or per-actor physical heap isolation claim.

## Contract

- `define enum Light as Red ... end` declares a *nominal* enum, not an integer or string alias.
- The compiler constructs `OresEvalRootNode.NominalEnumValue` with a private constructor. Its semantic identity is (defining source unit, enum name, case name, internal tag).
- A nullary variant (`payload() == null`) is immutable and qualifies as a scalar-like, direct actor-mail value. It crosses PRIVATE, SHARED and UNTRUSTED logical mailbox paths without JSON, object serialization, deep copy or an actor-owned heap pointer.
- The runtime retains ordinary mailbox slot/backpressure, aggregate/actor logical-byte charges (24 bytes per nullary symbol), sender/generation checks, cancellation and scheduling.
- A payload-bearing enum variant is **not** an immediate symbol. This slice intentionally rejects it from actor mail, including nested containers, pending explicit payload ownership and borrow-checking support. Never mark the entire ADT class Sendable based on its tag.
- No public static `Symbol.of(String, String)` or guest-addressable symbol factory is added. An unrelated Java host object or `SharedState` implementation is not a trusted symbol.
- `isScalar` dispatch recognizes only the concrete final trusted class with null payload; containers are still isolated/copied per the existing ActorRuntime transport policies.
- Name equality does not erase defining-unit identity; source aliases and hot reload must not conflate independently compiled modules.

## Boundaries

A JVM object is an implementation detail: the *language* type is the immutable nominal symbol. Direct local actor-mail transport does not mean actors gain permission to access one another's private mutable allocations.

The current Java actor substrate provides logical slices in one JVM. It does **not** prove independent physical per-actor heaps. Cross-process, Volucro VM and separately instantiated Wasm linear memories need a versioned symbolic transport encoding with authenticated type/unit identity, not pointer sharing. That separate boundary is intentionally out of scope.

Interning, if later introduced, must be runtime/module-lifetime-scoped and generation-fenced. Do not use process-global strings or raw declaration-order tags as long-lived wire identities.

## Acceptance tests

Run on an authorized exact-head runner with **nonzero executed steps**:

```sh
mvn -B -ntp -Dtest=ActorNominalEnumSymbolTransportTest,EnumMatchArmSyntaxGuestTest -DfailIfNoTests=true test
```

- The compiler/evaluator guest test executes canonical `.atto` enum matches.
- `ActorNominalEnumSymbolTransportTest` constructs synthetic compiler-shaped values *only in the host test* via reflection on a private constructor to exercise real mailbox handling. It verifies exact identity-preserving delivery across three local actor kinds, nested immutable symbol leaves with container-copy isolation, source-unit identity, non-numeric type semantics, and fail-closed forged/payload-bearing objects.
- Reflection fixtures **are not a compiled .atto actor-mail end-to-end proof**; a real guest actor sending the typed enum remains a required next test.
- Require a generated JUnit/Surefire report and executed job-step receipt, plus review of the exact SHA. A zero-step CI job is runner admission failure, not test evidence.

## Dependencies

- Stack this PR on Java enum expression-value PR #2847 and its underlying Java nominal enum PR #1598 (itself based on #1547). Do not merge this PR before the compiler dependencies.
- Reconcile with actor mailbox hardening PR #2838 when rebasing after its integration.
- Add full guest-actor `.atto` and cross-backend parity tests before claiming the feature complete.
