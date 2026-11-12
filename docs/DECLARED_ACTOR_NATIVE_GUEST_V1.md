# Native Atto declared actor: executable stateless v1 (draft)

This is the first **manifest-gated guest execution slice**, not a complete
stateful Counter, native AOT proof, supervisor, or backend-parity claim.

## Trust boundary

`PackageNamespaceBuild.compileStatelessActorGuestV1()` reads one canonical
`.atto.pkg.toml` source snapshot. The **same** parsed bytes are checked by
`ActorContractWitness` and `ActorMethodIndex` before the trusted compiler
assembles an ordinary in-memory actor AST and calls the normal
`OresCompiler.analyze` (type and ownership checking).

There is no guest-callable compiler flag. Ordinary `build()` and raw
`IncrementalCompiler.compile(Map)` retain their existing fail-closed gates
for general declared actors. The new method is an opt-in trusted-host
entrypoint whose returned AST must be passed through the existing one-shot
`OresLanguage.openPrecheckedProgram` lease before Truffle evaluation.

This first slice has these deliberate limits:

- Exactly **one** declared shared actor and one Atto `main` entry.
- One namespace, no imports, no named modules or additional declarations.
- One private `receive(ActorMail<T>)` callback, plus optional private lifecycle
  hooks and exactly checked public actor-contract methods.
- **No** attached actor state. Never convert attached state into legacy
  mutable actor fields: this would bypass checked `critical-read/write` loans.
- External method receiver identifiers such as `c` are not yet rebound.
  Methods can use the implicit actor-local `self`; a reference to an external
  alias not proven by the normal typechecker fails admission.
- No user-provided CONTROL notice, mailbox capability, actor reference,
  permission or runtime class is introduced by this compiler-only lowering.

## Physical native Atto fixture

`contracts/declared-actor-native-guest-v1/` contains a complete
`.atto.pkg.toml` project with independent source units:

- `src/counter/contract.atto`
- `src/counter/actor.atto`
- `src/counter/method.atto`
- `src/counter/start.atto`
- `src/counter/receive.atto`
- `src/counter/main.atto`

The positive guest test reads those exact physical files, loads a
manifest-owned package, obtains a **typechecked** AST, and executes it in
the real Oreslang Polyglot context with `allowAllAccess(false)`.
The fixture expects `1|7`: an actor-local startup output followed by
the actual mailbox payload. Atto methods implement both behaviors; Java
only supplies the existing compiler and actor scheduler.

The suite also checks mismatched contracts, missing receive, attached state
rejection, raw map compiler bypass rejection, unlowered receiver aliases,
and rejected `handle_state(critical-write ...)`.

## Exact-head execution gate

From a verified JDK25/GraalVM25 checkout of this branch:

```sh
mvn -B -ntp -Dtest=DeclaredActorNativeGuestV1Test,DeclaredActorCompilerManifestGateTest,DeclaredActorContractWitnessTest,ActorContractTargetCompilerIntegrationTest -DfailIfNoTests=true test
```

Require nonempty Surefire XML with **zero** failures, errors and skips,
and include the source head/tree and all physical fixture Git-blob
identities in the receipt. Zero-step GitHub Actions jobs are
**NOT_EXECUTED**, not successful tests.

A private CI carrier is acceptable only with authorized access, no
source-org secrets or cross-organization token checkout, an exact-tree
identity/matching Git-blob proof, and an isolated disposable branch.
A copied subset is not evidence of full source-tree execution.

## Next implementation after this gate

1. Introduce a dedicated `ActorStateAccessExpr` plus typed
   `critical-read/write` MIR instructions. Lifetime/alias checking must
   reject escaping loans, reentrant writes and suspension under the permit.
2. Bind external receiver aliases to an actor-context capability **only**
   after checked lowering; preserve private hook and CONTROL exclusivity.
3. Execute the actual stateful four-message Counter fixture. Assert the
   sealed state total is 10, then run full JVM/Native Image proof.
4. Build native Atto `ServiceActor` and `SupervisorActor` after those gates,
   then run independent LLVM, Wasm, Forge and .NET conformance.

**Never treat this stateless guest test as proof of stateful actor readiness.**
