# Guest enum symbols and local actor mail — compiler bridge

Status: **draft integration**, not cross-backend parity or a compiled
guest-to-guest actor-mail proof.

This work is stacked on the existing nominal enum compiler draft #2847
(which itself depends on #1598). It intentionally reuses the **same**
`OresEnumSymbol` and `OresSymbol` source files as the main-based
runtime draft #2853. The overlapping runtime files must be reconciled
by exact-base rebase before any merge. Do not merge two competing
representations or silently overwrite main.

## Implemented

- The actual Truffle guest evaluator constructs `OresEnumSymbol`
  values for **closed, entirely nullary enums**. Each declaration
  snapshot is compiler-built from `NominalEnumRegistry`, and the
  evaluator's immutable map is shared safely across actor carriers.
- An ordinary enum case expression such as `Light.Red` is a nominal
  typed symbol, not a numeric ordinal, string, open `#Symbol`,
  pointer-identity token, or authorization capability.
- Value equality, constructor-pattern matching, returned-type shaping,
  runtime type checking and asynchronous detachment recognize these
  symbols and validate defining code-unit plus full closed enum schema.
- The local JVM actor runtime treats checked nullary symbols as immutable
  scalars: private and shared actor mail retains the same value without
  byte serialization, and mailbox quota checks charge the full closed
  schema with cached O(1) logical accounting.
- Unknown, host-mutable objects still fail closed. UNTRUSTED actor
  ingress rejects enum symbols until authenticated export admission
  exists, even when nested in collections.
- **Mixed and payload-bearing enums** still use the old
  `NominalEnumValue` path. They do not become sendable scalars by
  virtue of a nullary case and retain their ownership restrictions.

## Executable regressions authored

- `EnumSymbolCompilerBridgeGuestTest` parses/typechecks and executes
  `src/test/resources/atto/enum-local-mail/closed-nullary-symbols.atto`
  through the real Java/Graal language context.
- `OresEnumSymbolActorMailTest` directly exercises PRIVATE and SHARED
  host actor runtime mail delivery, logical symbol identity, quota
  accounting, and direct/nested UNTRUSTED rejection.
- A negative guest fixture keeps the existing actor lexical isolation:
  same-file enums are not automatically in scope for an actor just
  because their values are immutable.

**Not yet implemented:** a positive real `.atto` actor receiving
`ActorMail<Light>`. Existing compiler rules deliberately reject an
actor's ambient same-file enum access. That positive test requires
an authenticated **explicit import** and a checked nominal type
manifest available to the receiving actor's compiler context. Do
not remove this guard or forge a same-file shortcut.

## Verification / merge gates

1. Execute `mvn -B -ntp
   -Dtest=EnumSymbolCompilerBridgeGuestTest,OresEnumSymbolActorMailTest
   -DfailIfNoTests=true test` at the **exact PR head and tree**.
2. Confirm positive guest output, actual JUnit/Surefire results,
   negative compiler diagnostics, nonzero runner steps and attempt
   provenance. A workflow run with `steps=[]` or `null` is
   `RUNNER_ADMISSION_FAILURE / NOT_EXECUTED`.
3. Integrate imported enum manifests and a positive typed guest
   receiver, then prove delivery, backpressure, STOP, cancellation,
   supervisor forwarding and stale-code-generation rejection.
4. Verify LLVM O0/O2, .NET NativeAOT, Wasm, Forge and Java AOT/JIT
   semantics with real compiled fixtures; host-only tests cannot
   substitute for compiler-backed evidence.
5. Obtain independent review and resolve conflicts against
   #2853 and the dependency chain. Do not mirror private source to
   another organization without explicit source-side authorization;
   a different GHA minutes budget alone is not permission.

Different VMs, processes or Wasm linear memories still require
explicit authenticated transport encoding. This local scalar bridge
does not provide cross-domain raw memory sharing.

## Detached-graph authorization hardening

The older enum-compiler stack preflighted incoming mailbox graphs before
creating the detached image. An untrusted or concurrently mutated Java
collection can change between those traversals. The bridge now validates
and re-authorizes the **actual prepared graph** before a mailbox quota
reservation or publication, including UNTRUSTED enum export denial and
nested runtime capability checks. Actor output publication and public
`freeze()` also validate after the copy.

`freezeRejectsCapabilityInjectedByMutableMapDuringSnapshot` constructs a
Java Map whose entrySet changes after two read traversals. The first
preflight sees harmless data; the detached graph contains an ActorRef.
It must be rejected instead of becoming authorized by an earlier snapshot.
This is an authored regression pending executable CI.
