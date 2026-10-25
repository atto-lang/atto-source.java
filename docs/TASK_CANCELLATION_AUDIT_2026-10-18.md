# Task cancellation audit — 2026-10-18

Scope: draft PR #1402 (runtime authority only). **Not a source-level Task implementation, not proof of task quiescence, and not production approval.**

## Findings and corrections

| Finding | Impact before correction | Correction |
| --- | --- | --- |
| Published cancellation flag before reason | A racing reader could observe \`isCancelled() == true\` with a null reason | Publish the immutable reason before the volatile cancellation flag |
| Late observer bypassed revocation | Registration racing a blocked revoker could notify observers before state revocation | Buffer late observers until the entire admitted revocation batch finishes |
| Direct recursive linked callbacks | Long chains and cycles could overflow stack; a swallowed error could stop downstream propagation | Dispatch host-only cancellation callback batches with a thread-local iterative queue |
| Repeated \`linked:\` prefixes | Chain length caused unbounded growth in reason strings | Bound reason length to 256 and normalize already-linked reason |
| Terminal token retained owner | Retained cancelled child could hold its former ancestor graph | Clear the ownership pointer atomically when cancelling |
| Retained Subscription pinned graph/hook | Caller holding a delivered/closed subscription kept owner and callback reachable | Release owner/callback after one-shot delivery or detachment |
| Java class was public | Proposed runtime-only cancellation authority exposed a broad Java API before guest capability admission existed | Make class and host API package-private; move tests to runtime package |

## Local reproducible verification

- Java: OpenJDK 21 \`javac\` and \`java\`; no GraalVM/JDK 25 or Maven integration available in the local harness.
- Exact staged source/test byte identity is checked by Git blob SHA against the PR head.
- Fifteen JUnit-style test methods compiled and executed using a minimal local annotation/assertion shim. They are *host primitive tests*, not real repository JUnit/Maven runs.
- Full 15-case suite repeated three times under \`java -ea -Xmx128m\`: 15/15 each run.
- Includes 100 cancellation-vs-adoption iterations, 200 cancellation-vs-late-listener iterations, and an 8,000-link iterative cancellation chain.
- \`javac -Xlint:all\`: only a Java 21 deprecation/removal warning from checking \`ThreadDeath\`, no compilation failures.

## Remaining semantic risks and blockers

1. **Physical teardown:** a cancelled \`OresFuture\` is NOT evidence that a guest turn left its carrier; a follow-up \`TaskHandle.get_done()\` needs the scheduler quiescence barrier (Java issue #1403).
2. **Permissions:** package-private Java API is defense in depth, not guest authority checking. Actor/tenant ownership, on-behalf-of transfer permission, and cancellation links must be checked before source exposure.
3. **Failure semantics:** asynchronous cancellation still needs explicit failure reporting, cleanup, actor serial/reentrancy enforcement, and a supervisor policy for \`do task\`.
4. **Callback cost:** trusted runtime callbacks are synchronous; a slow or blocking callback may delay a cancellation invocation. No bounded latency is claimed.
5. **Scheduler scaling:** a global graph lock is a conservative v1 correctness mechanism; stress and per-domain sharding should precede multi-tenant deployment.
6. **Compatibility:** the current channel/actor contract still treats actors as the only independent identities; proposed source Tasks supersede only that portion after parser/compiler/runtime integration. LLVM parity: issue #651. Shared source conformance: issue #108.

## Source CI / private mirror gate

Source repository: \`ores-truffle-oreslang/oreslang-source.java\` (private repository, numeric ID \`1400792274\`).

At this PR's current checked head, GitHub Actions reports failed jobs before any steps executed (sampled \`ci\`, \`fast-ci\`, and \`Target ABI\`; seven jobs all showed zero steps). Therefore no Maven/Graal/LLVM result has been established by GitHub Actions.

**Do not mirror this private source to another GitHub organization yet.** The repository's \`docs/PRIVATE_CI_MIRRORS.md\` and open security issue #962 require an independently reviewed publisher, authenticated and pinned source/destination numeric IDs, both verified private, protected provenance, an approved immutable allowlist, policy rechecks before writes, and exact-tree verification. Issue #832 records a previous public CI mirror exposure whose Git objects persisted after ref resets.

The existing security policy explicitly states the main allowlist is empty and private source transfer is unauthorized until the control gaps close. A public source-free CI probe could prove a runner works but cannot validate this private Java tree. Do not describe local host testing or a source-free carrier test as source-exact CI.

## Exit criteria

- Exact-head real CI executes source compiler/test steps on an authorized runner.
- Concurrency tests pass repeatedly under JDK 25/GraalVM for scheduler admission, cancellation/complete/close races, and task teardown.
- Runtime cancellation links do not expose lateral actor cancellation authority.
- Owner transition and \`get_done()\` timing are proven under real Java and LLVM continuation lowering.
- Security reviewer verifies provenance/mirror controls before any private cross-org source transfer.


## Fatal callback revocation-barrier hardening — October 18

**Reproduced against the original exact Java source:** a trusted
`onRevoke` callback throwing `LinkageError` interrupted the cancellation
batch before remaining revokers were delivered and before
`revocationComplete` was published. Original and late `onCancel`
observers could then remain pending indefinitely.

**Correction in this branch:** retain and rethrow the first fatal
`VirtualMachineError`, `ThreadDeath`, or `LinkageError`, but continue
delivering other admitted revokers/observers and publish the revocation
barrier first. The iterative thread-local dispatcher also drains
previously queued nested cancellation batches rather than dropping them
when a preceding callback reports a fatal Error. Ordinary nonfatal
callback failures retain the existing best-effort suppression policy.
Under catastrophic VM exhaustion, successful cleanup cannot be
guaranteed; this is not a global recovery claim.

Two new committed JUnit regressions cover a fatal revoker and a nested
fatal observer; **17** stage-1 JUnit methods are now present. A
source-local Java 21 test harness using the exact original file reproduced
`revoked=0 observed=0 lateObserved=0`; the patched source reported
`revoked=1 observed=1 lateObserved=1`. In the same host harness, **2,400**
normal and nested-cancellation test cases passed. This is direct
primitive-level validation, not Graal/Maven/JUnit execution against the
complete OresVM scheduler.

Both the stage-1 and the stacked stage-2 draft branches contain matching
semantic fixes and regressions; rebase stage 2 onto the reviewed stage-1
head before merging. Source Actions and private-source export restrictions
remain unchanged.
