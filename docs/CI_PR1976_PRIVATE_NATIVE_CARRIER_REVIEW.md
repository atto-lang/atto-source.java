# PR #1976 GraalVM Native Image — private CI carrier review packet

**Status: REVIEW ONLY — NO SOURCE TRANSFER AUTHORIZED.**

This document is an evidence and requirement packet. It does not modify
`.github/private-ci-approved-repositories.txt` or
`.github/private-ci-carrier-contracts.json`, does not invoke the mirror publisher,
does not grant anyone transfer authority, and must not be interpreted as a waiver
of the source security controls.

## Exact identities and scope

| Role | GitHub repo | Immutable repository ID | Visibility |
| --- | --- | ---: | --- |
| Source | `ores-truffle-oreslang/oreslang-source.java` | `1400792274` | private |
| Candidate runner | `cliptown/cliptown-e2e` | `1324410267` | private |

The two identities were read from GitHub on 2026-10-24. The candidate is
not archived, disabled, or a fork and its default branch is `main`.
**This is only a point-in-time metadata check, not transfer admission.**
Every assertion must be revalidated immediately before all source operations
against the protected, independently reviewed policy.

The exact PR #1976 head must be re-fetched **at authorization and execution
time**; its SHA must equal the head of the still-open PR targeting source
`main`. The source root Git-tree SHA must also be pinned and compared. Do
not substitute a synthetic pull-request merge commit for the PR head.

## Existing proof (source-free, not an Oreslang build)

1. GitHub Actions [carrier run 40065041539](https://github.com/cliptown/cliptown-e2e/actions/runs/40065041539)
   checked out an isolated, connector-created **synthetic four-file Git tree**,
   configured GraalVM, built a 5.44 MiB native executable through a Maven
   Native Image profile, and ran the executable successfully.
2. The synthetic root Git tree was
   `c99e3d0644442c7afaaf69fb18e65ed5b8864b8c`;
   carrier commit `1968a6f87dd9590277aa2f9a828921bf8b217751`;
   synthetic workflow blob `2a3f437ed053403f4a0afe3437b3c7a93fb97201`.
3. This synthetic workflow is **not suitable for confidential source
   execution**: it does not implement the full source-attestation,
   authorization-ref, isolated-blob-checkout, credential-scrubbing,
   offline-build, or controlled-egress boundaries.
4. Previously executed source AOT, hybrid, and isolate build evidence is
   recorded in [PR #1976](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1976),
   but belongs to an older source commit.
5. Source Actions at the latest PR head repeatedly end with **zero executed
   job steps**; this is not a compiler or runtime test result.

## Mandatory independent blockers (fail closed)

- [Security issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
  is open. Its protected-origin/preflight checks must be wired into the
  **actual V3 source publisher**, rather than relying only on an adjacent
  standalone script. A test runner cannot override this block.
- [Authority PR #1724](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1724)
  is an explicitly documented HOLD/no-op authority PR. It must not be
  approved/merged as-is. The independent incident-owner dispositions,
  security review, exact reviewer identity and source policy SHA required by
  that PR must be demonstrated; similarly numbered issues in an unrelated
  project do not establish a disposition.
- [Branch-protection issue #904](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/904)
  remains unresolved. If protected-main mode cannot be demonstrated, V3's
  immutable exact review-attested mode must pass **without bypass**.
- The **current source allowlist is empty**. The existing carrier contract
  contains a separate `opto-sync-test/go-java-validation` entry restricted
  to **PR #1482** only; it is not authority for PR #1976, and no carrier
  should be silently added or reused for a new PR.
- Candidate `cliptown/cliptown-e2e` `main` was reported **unprotected**.
  It contains unrelated private project state. A proper source-free,
  immutable carrier base and an independently reviewed isolation workflow
  would be required; a normal checkout of all carrier blobs would violate
  confidentiality assumptions.

## Required carrier contract before enabling the gate

An independently reviewed **new** carrier contract and exact test plan must pin:

1. Source and destination names **and immutable numeric IDs**, both private,
   active, distinct, and rechecked against fresh live GitHub metadata.
2. A source-free carrier `main` **commit SHA** and exact reviewed workflow
   **blob SHA**. Workflow must verify immutable authorizing ref, receipt,
   manifest, source tree and source PR-head commit before running code.
3. A unique exact test-plan path and **blob SHA** allowing only PR #1976,
   with a pinned `pom.xml` blob and curated fixed command arguments and
   execution timeouts; the user must not be able to inject shell commands.
4. Only sparse + blob-filtered checkout of the source subtree and reviewed
   control files. Prove unrelated carrier files are absent from both
   worktree **and** local `.git/objects`; do not offer the test code an
   authenticated `git fetch` path.
5. Before code execution, drop the checkout credential/remote and scrub
   `GH_TOKEN`, `GITHUB_TOKEN`, HOME/XDG auth, secrets, and unnecessary
   filesystem access. Restrict outbound network. The reviewed Maven plan
   must use **offline mode** after separately prepared, verified dependencies.
6. The source-executing job must have `contents: read`; ref cleanup is
   a separate narrow-permission job. Deleting refs does **not** securely
   erase source blobs from GitHub storage.
7. Independently verified carrier isolation under adverse conditions:
   altered protected workflow, unauthorized ref, revoked approval,
   swapped commit/tree, wrong repo ID, visibility change, stale PR head,
   missing offline dependencies, leaking build output and network egress.

The tested synthetic four-file workflow may serve as **runner-capacity
evidence only**. Never reuse it as the source-carrying production workflow.

## Exact native test plan to execute *after* approval

All selected commands should use a reviewed offline runner environment and
immutable checkout. PR #1976's smoke script now supports `ORES_NATIVE_IMAGE_OFFLINE=1`, which
passes `-o` to Maven and fails closed on invalid flag values. **36 tests passed (prior head)
locally against the exact committed script/test Git blob versions**, including
two offline success configurations, invalid-mode denial, and new
fail-closed checks for duplicate summaries, unexpected guest FAIL/PASS
records, and missing PASS/SKIP results. The current 36-case suite also rejects
replacing one expected test with a duplicated PASS, an unexpected SKIP ID,
and duplicate expected PASS/SKIP identities. This is native mock verification,
not current Oreslang GraalVM build evidence. A future carrier
must still prove a source-free verified dependency cache, effective egress
restriction and hardened sandbox; a Maven offline flag alone is insufficient.

- Baseline: `mvn -B -ntp -o -Dores.runtime.carriers=native verify`.
- Native AOT compressed32: build `native-aot`, confirm `--no-fallback`,
  inspect binary format/embedded manifest, execute `together.ores`,
  `native-core-library.ores` and `native-testing.ores`.
- Native AOT native64: same tests with `native-aot,native-ref64`,
  assert the baked `native64` reference mode.
- Native hybrid compressed32: same tests using `native-hybrid` and confirm
  the baked hybrid mode, with no JVM fallback.
- Native isolate: build `native-isolate` and execute Oreslang via the
  JNI-backed strict isolate, plus stdlib and root-await integration.
- All jobs must expose executed-step logs and exact source commit/tree SHAs.
  Native tests must validate guest output and process status, not green
  labels or a mock binary alone.

## Reviewer decision (must be independently recorded)

- [ ] Independent incident owner resolved the specific P0 incident blockers
- [ ] Source V3 live/private/provenance publisher closes #962 with tests
- [ ] Protection or exact review-attestation source policy is demonstrable
- [ ] Dedicated carrier source-free base, isolation, pinned workflow and
      PR-1976-only plan have been reviewed and executed in source-free mode
- [ ] Destination is newly approved in protected source allowlist/contract
- [ ] @ORESoftware (independent of author) approved exact authority head
- [ ] Source code export has not begun before all of the above pass

**Until every requirement is satisfied: do not transfer private Oreslang
source bytes, create source blobs in other repositories, authorize source
Actions, or merge PR #1976 based on synthetic evidence alone.**


## Further local verification (2026-10-24)

The exact checked-in source-side build gate Git blob `6493dd743a5e8e55b8c4d57d12be6cf6d11f6134` and regression test Git blob `1e23a810c0c010d95e5c34d74366caa688ac1b78` both match the bytes exercised locally. All **36/36 (prior head)** native mock contract cases passed, and both shell scripts passed `sh -n`. This is a source-free runner-gate contract proof, not an exact-current-source GraalVM build.

[Exact-source CI run 40112643693](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40112643693) failed before any job executed a step (test, AOT, AOT reference64, hybrid and isolate all zero steps). Never report this as a compiler failure or successful build. The actual platform-conformance P0 issue IDs #22, #77 and #127 remain open; no private source transfer is permitted.

## 2026-10-24 source PR numeric identity security review

[Draft hardening PR #1993](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1993) adds per-write live source-private state checks and binds **both** the source PR head and base repositories by immutable numeric repository ID `1400792274`, not just repo slug. It also denies malformed/missing repository/PR metadata and changed source privacy or identity between source-bearing writes. Six focused isolated test groups passed locally; the full V3 regression suite has **not** run in GitHub Actions because source-side preflight [run 40113480252](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40113480252) and source-free negative suite [run 40113480261](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40113480261) executed zero job steps. This is further security hardening, **not** closure of #962 or authorization to export private source.

## 2026-10-24 native actor admission and check/execute split

PR #1976 now requires the native `--check examples/callables.ores` path to admit without guest output and the native execution path to produce exact `120:9:13`. The same build gate checks valid shared/private actor declarations and rejects the checked-in private-actor SharedMutex/read-only sharing authority-laundering fixture. The local `sh -n` and compiled native C-mock regression suite passed **36/36 (prior head)** cases. Exact Git blobs: shell gate `77044ae023309270b7f7d61d012dbc2f3af6cebf` and test suite `d15f74f46bd3b5fe643e8c4f7c6161aec0c27737`. [Exact-commit source CI run 40114707376](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40114707376) still failed with zero executed steps in all five jobs. This is not actual Oreslang guest-runtime validation, and the destination approval list remains empty.

## 2026-10-24 native actor-denial exact-reason safeguard

The latest [Native Image PR #1976](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1976) fixes a false-positive in the negative private-actor admission smoke. An arbitrary nonzero CLI return is not evidence of enforcing private actor isolation: the native executable must reject `private-actor-sharing-invalid.ores` with exactly **CLI status 1** plus `Oreslang isolate denies capability SHARED_MEMORY` or `ACTOR_SHARE_READONLY`. Unexpected syntax errors, unrelated `NETWORK` denials, false admission, missing fixtures and wrong exit statuses cause failure. Two negative mock scenarios also check the gate's exact rejection reason.

Source-free mock suite now lists **39 cases**, but the *full updated suite has not been executed* in this turn. Eight focused local shell/C-mock scenarios and syntax checks passed. Latest committed source Git blobs: gate `3cefe3f8d2106629a6e370669c6e5f0cfca965f1`, test suite `c06f2151ac04ab119b82be1c7a3412e3d5e7286d`. [CI run 40128694431](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40128694431) failed with **zero executed steps in all five jobs**. The carrier's security approval, incident disposition and independent review requirements remain in force. No private source was exported.

## 2026-10-24 checked-in native channel/select smoke

[PR #1976](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1976) now executes the existing `examples/native-channel-select-smoke.ores` under the built native image, requiring exact `first:7:9` output: the primary branch wins and the unselected buffered secondary message remains available. Three new negative mock cases target an empty result, a wrong selection winner and a lost message. The complete source-free shell/C mock suite contains **45** cases but remains **unexecuted as a full current-head suite**. The new isolated channel verifier predicate was run with a compiled C mock locally and passed **8/8** focused cases; this does not prove GraalVM channel behavior.

Exact native gate source blob `f7295907fe023c34ae987380edd597f1d7284c89`, exact mock fixture source blob `f231bdc713bff46aa55e7994fc03422f39a63c68`, PR head commit `0beaea59eb44a0329979e478363fa5ca57cff9eb`. [Exact-commit CI run 40137791157](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40137791157) failed **before any steps in all five jobs**. The alternative organization's source-free [GraalVM proof run 40137488274](https://github.com/cliptown/cliptown-e2e/actions/runs/40137488274) succeeded; no private Oreslang source was mirrored. Three P0 incidents #22/#77/#127 in `oreslang-platform-conformance` remain open and the source allowlist remains empty.

## 2026-10-24 fail-closed native negative-result oracle

The source-side Native Image verification regression harness was hardened to require **specific failure status and stage-correct stderr diagnostics** on every negative scenario. This eliminates a false-positive where an unrelated Maven or guest-admission failure could pass a negative test. Source commits `5f01e624b621e475f9028bf9dc74b59ac07870e2`, `a3b47f094390da7dad1201525429350edd53b4c7` (POSIX shell case syntax), and `dd77c657b78f5f107d54670f94638ad63fc65a6e` (status-zero actor diagnostic) are in native gate PR #1976.

The latest inspected test-suite blob `eb00387f7dbb06cc5f0e87a7f8c8ba5004154f54` includes **47 cases: six positive, 41 negative**, including two newer check-only stderr rejection cases. Static audit found all 41 negative cases classified with no duplicate names. A locally reconstructed POSIX matcher passed `sh -n` and **120 focused expected-status/diagnostic checks** across those 41 names. This **does not constitute executing the full 47-case source regression shell script** or GraalVM/Oreslang source execution.

Source [CI run 40139418683](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40139418683) failed with zero executed steps for test, AOT, ref64, hybrid and isolate jobs. The source private carrier allowlist remains empty and platform-conformance security incidents #22/#77/#127 remain open. Alternative-org runner success is only **source-free synthetic/toolchain evidence**; neither it nor these local checks authorizes transferring private Oreslang source.

## 2026-10-24 source-native output integrity

PR #1976 fixes trailing-blank-output false positives: shell command substitution removed terminal LF bytes before strict ORES_TEST parsing. The native gate now preserves the raw stdout file and checks the guest exit status and stderr. A source-free adversarial regression appends a blank record after the valid test summary. The suite contains 55 committed cases, not yet run end to end at the latest head. Focused local checks: 11 raw-output parser cases and 8 shell exit/stderr cases passed. CI source run 40150423224 failed before any steps. Protected source-carrier authorization remains pending; no private source transfer was performed.

## 2026-10-24 strict exact-byte native smoke checks

PR #1976 now enforces raw stdout byte equality on callables, core library, channel/select, Future/select and root await. Previously POSIX command substitution trimmed trailing LFs and hid extra blank output records. The new POSIX helper permits the exact result with zero or one terminal LF and rejects extra bytes, retains guest failure status and rejects nonempty stderr. Five new source-free negative regression cases append extra blank lines. Pinned source head `f0ff756bc0d78d9a1fc5d4635797f52b47d9d37e`; gate blob `546a7a1816ee7b29d6b154029c250d38cfc06a75`; test blob `348962e15d10b37a94edcb6adc8bf4deef00dc75`. The suite lists **60** cases (6 positive, 54 negative). **10/10 focused local executable/shell cases** passed; full committed 60-case suite not yet executed.

[Source CI 40157757634](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40157757634) failed with **zero steps in all five jobs**. [Alternative-org source-free hosted runner probe 40039270299 attempt 4](https://github.com/cliptown/cliptown-e2e/actions/runs/40039270299) passed all three job steps, without verifying real Oreslang code or remaining organization billing minutes. Private carrier destinations remain zero and independent P0 security incidents #22/#77/#127 remain open. No private source transfer is authorized.
