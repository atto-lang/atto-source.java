# AttoLang Java/GraalVM actor-critical guest canary

This change is an executable **compiler-backed test declaration**, not an executed CI result. It is a precursor to the separate two-reader ownership and `rt take` acceptance program described in [platform-conformance issue #522](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/522).

## Real guest metamorphic control

The JUnit metamorphic tests in `CriticalActorGuestExecutionTest` load the canonical `examples/critical-actor-checked-v1.atto` source into the real Graal/Truffle language Context through `OresCompiler.parseAndTypeCheck` and `context.eval`.

It compares two separate evaluations:

| Variant | Source initialization | Expected observed stdout |
| --- | --- | --- |
| Canonical | `let int value = 40;` | `42` |
| Mutated +1 | `let int value = 41;` | `43` |
| Mutated −1 | `let int value = 39;` | `41` |
| Critical-body mutant | `value = value + 3;` | `43` |

The test requires one and only one occurrence of the source replacement target and uses a distinct polyglot Source filename for the mutated variant. A source-insensitive stub that always prints `42` cannot pass this test.

This does **not** prove actor isolation, simultaneous readers, borrow exclusivity, generation transfer, or Native Image/AOT behavior. It is a restricted actor-critical JVM guest source-sensitivity canary.

## How to run (once an authorized runner is admitted)

```sh
mvn -B -ntp -Dores.runtime.carriers=java -DfailIfNoTests=true \
  -DskipTests=false -Dmaven.test.skip=false \
  -Dtest=CriticalActorGuestExecutionTest,ActorBorrowCompilerAdmissionTest test
```

The existing workflow `.github/workflows/critical-suspends-java-admission.yml` pins original PR-head checkout, records the Git root tree, and requires the new test name inside a nonempty Surefire XML report with no failures, skips or errors. A separate artifact carries the original source SHA, tree, canonical fixture digests, and compiled `OresCompiler.class` hash when present.

These artifacts are explicitly candidate-only until another verifier binds their exact run/job/attempt, step execution, source tree, relevant blob hashes and test output. An artifact uploaded by `if: always()` is never by itself proof that tests ran.

If GitHub reports `steps=null` or `steps=[]`, classify it as runner-admission failure, **not** a compiler failure or a passed guest test.

## Compiler-owned borrow admission

The added `ActorBorrowCompilerAdmissionTest` defines **13** source/compiler admission checks: two shared borrows coexist; an exclusive borrow is admitted alone; a writer is rejected with two live readers; `rt take` is rejected while borrowed; and a stale owner use after move is rejected. Eight additional tests cover single reader/write exclusion, write/read exclusion, double mutable borrow, owner rebinding during a read, borrow cleanup across lexical blocks, and double `rt take`. These use canonical `rt borrow`, `rt mut borrow`, and `rt take` syntax already supported by the Java parser and OwnershipChecker. The tests are authored, but **have not yet executed** under hosted CI.

Run with the above guest test by setting:

    -Dtest=CriticalActorGuestExecutionTest,ActorBorrowCompilerAdmissionTest

These are compiler ownership checks, not actor heap generation-fencing runtime evidence.

## Follow-up formal refinement

Complete ACT-BORROW-001..005 with actual actor-owned runtime tests for read/write exclusion, transfer generation fencing, cancellation and actor termination. An ordinary `rt take` of an array is a compile-time move; it does not prove a supervisor-mediated actor transfer. Execute identical canonical .atto blobs on LLVM, .NET NativeAOT, Wasm and Forge, retaining backend-specific guest receipts. These requirements remain **NOT_EXECUTED**.

## Hardened exact-head CI XML evidence audit

The compiler/guest workflow no longer qualifies a test by grep-searching for
a method name inside an arbitrary XML string. It now runs a JDK 21 source-mode
validator in `tools/VerifyAssuranceSurefire.java`.

The validator requires nonempty, reasonably bounded reports for all eight
expected test suites, matching suite and testcase class identities, an exact
executed-test count, zero errors/failures/skips, unique testcase names, and
every required security method exactly once. It uses a hardened JAXP parser:
DOCTYPE, external entities, XInclude, and external DTD/schema access are
disabled. Before Maven runs it also checks **20 invalid XML variants**, including
fabricated failures, skipped and error children, nested/duplicate test cases,
forged suite/class identities, incorrect and overflowing counters, and XXE.

A separate `suite-gate-completed.json` file is written **only after** the
actual JUnit XML passes structural validation. The workflow may upload a
candidate-only checkout/fixture record on failed attempts, but the existence
of that artifact is never promoted to a passed guest test. The test gate and
run/attempt/head/tree must all be checked by an independent verifier.

This parser is a safeguard against accidental/malformed test XML and misleading
grep matches; it is not a cryptographic attestation and does not make a
compromised compiler, malicious test suite, or CI runner trustworthy.

## Evidence artifact publication hardening

The Java guest workflow now pins `actions/upload-artifact` to the audited
immutable commit `ea165f8d65b6e75b540449e92b4886f43607fa02` rather than a
floating `@v4` tag. Evidence uploaded with `if: always()` is candidate-only
on failure: the independent gate must use the separate
`suite-gate-completed.json` written only after successful Maven, XML
inspection, exact-tree readback and compiler/fixture hashes.

## Independent receipt readback integration

The pass-only `target/atto-guest-evidence/suite-gate-completed.json` now includes
the `GITHUB_JOB` key, SHA-256 of the compiled `OresCompiler.class`, and
SHA-256 of the canonical `critical-actor-checked-v1.atto` fixture, alongside
original PR-head/tree/run/attempt. These values are written **only after**
structural inspection of real Surefire XML.

The verifier requires the named checkout
`Checkout immutable original Java PR head` and named compiler/test execution
`Compile critical AST, Task authority and .ores Truffle guest tests`; a
successful setup-only step cannot satisfy the independent reviewer pin.

The independent gate in
[compiler-parity-website PR #7](https://github.com/ores-truffle-oreslang/compiler-parity-website/pull/7),
`tools/verify-assurance-receipt.mjs`, checks these fields against **reviewer-pinned**
hashes and a fresh GitHub PR/commit/run/job API readback. Distinguish the
GitHub display job name from the `GITHUB_JOB` key. A successful metadata
comparison is a candidate-only result; the gate does not download, sign,
authenticate, or evaluate the actual guest trace or artifact bytes. The
always-uploaded `checkout.json` does **not** qualify as pass-only evidence.

Never grant a read token to PR-controlled code. Run live verification from a
trusted independent checkout after CI runner admission is restored. Real Java
and Graal tests are still unexecuted until GitHub returns nonzero started steps.

## Scoped Bash-only exact-source gate (November 7, 2026)

The Java/Graal critical guest workflow now invokes
`tools/verify-critical-guest-inputs.sh` before Maven and immediately before
writing the **pass-only** Surefire receipt. The gate checks the actual bytes of
tracked compiler/test Java sources, resource inputs, Maven configuration,
`pom.xml`, the original workflow and the canonical `.atto`/`.ores` actor
fixtures against the immutable Git `HEAD` blobs. It rejects modified
`assume-unchanged` and `skip-worktree` files, untracked/ignored Java source
or Maven extensions, and missing/symlinked required inputs.

Both Git index and committed-tree path inventories must be readable and
identical, and filesystem enumeration failures are hard errors. The workflow
independently compares the gate and its test script to original-head Git
blobs **before invoking either script**, so a replaced gate cannot bless
itself. It repeats those checks before creating the pass-only completion JSON.

The scratch-Git negative harness at `tools/test-critical-guest-inputs.sh`
passed **14/14 locally** with Bash and Git; the local content matches the
corresponding exact GitHub file blobs. Cases include original-head positive
execution, head mismatch, ordinary edits, hidden `assume-unchanged` and
`skip-worktree` edits, ordinary and ignored injected sources, deleted
fixtures, symlink substitution, untracked Maven extensions, truncated
Git manifest output and a failing filesystem enumerator.

This is **SOURCE_ONLY_HOST_INTEGRITY**, not a real Maven, Graal, Truffle
guest or Native Image result. No hosted CI run was admitted during this
audit; keep Java guest execution and borrow refinement `NOT_EXECUTED`
until the private runner-allocation incident is resolved. This gate does
not claim comprehensive integrity of arbitrary host environment/toolchain
inputs outside the selected source and Maven surfaces.


## Eight-suite Java Surefire evidence

The Java workflow preserves **all eight** suites checked by
`tools/VerifyAssuranceSurefire.java` and emits
`target/atto-guest-evidence/surefire-xml-sha256.txt`.
The companion independent [assurance artifact verifier](https://github.com/ores-truffle-oreslang/compiler-parity-website/pull/7)
rejects missing or duplicate XML, altered manifest digests, suite count
mismatches, forged names, failed/skipped testcase elements, and XXE.
The `suite-gate-completed.json` receipt remains pass-only and explicitly
**NOT_INDEPENDENTLY_VERIFIED**. An `if: always()` artifact, including a
partial suite ZIP from failed CI, cannot be independently promoted to proof.

