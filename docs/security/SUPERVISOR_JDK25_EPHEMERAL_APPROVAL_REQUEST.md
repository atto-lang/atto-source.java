# Supervisor full-Java JDK25 source-repository runner gate — pending independent approval

**Status: PROPOSAL ONLY — NOT APPROVED.** This stacked draft adds a fail-closed CI choice for source repository `ores-truffle-oreslang/oreslang-source.java`. It does not authorize any cross-org source mirror and does not provision a VM or registration secret.

## Why this is required

The source organization has repeatedly produced failed GitHub Actions runs with `steps=null`. The escrow *standalone* Java source was verified under JDK21 and JDK25 in a separate three-blob private shadow test. However, the actual source tree (136 Java source files), Graal/Maven dependencies, and 16 supervisor `ActorRuntime` JUnit cases have **never been executed on PR #2885's full source commit**.

Current security source of truth, `.github/private-ci-approved-repositories.txt`, authorizes **zero** private destination repositories. `docs/security/private-ephemeral-actions-runner.md` specifies a dedicated, disposable runner *within the private source repository*. This draft follows that option. Do **not** use the existing cross-org three-file shadow test as authorization for a full source checkout elsewhere.

## New opt-in workflow behavior

`.github/workflows/supervisor-failure-escrow-host-v1.yml` defaults to an ordinary hosted Ubuntu worker and continues to execute the existing exact-blob `verify-supervisor-escrow-runtime-bridge-v1.sh` JDK25/Maven gate. It has no privileged permissions beyond `contents:read` and persists no checkout credentials.

Only if `vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER` is **exactly** `true` does it target `[self-hosted, linux, x64, oreslang-private-ephemeral]`. This path is permitted only when **all** conditions hold:

- `github.repository == ores-truffle-oreslang/oreslang-source.java`;
- event is a same-repo `pull_request` with exactly the head branch `ci/supervisor-ephemeral-jdk25-exact-head-20261111`;
- `vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA` equals the actual full 40-character **current PR head SHA**;
- the runner executes a pre-check before private checkout that repeats the identity, branch and SHA tests;
- checkout uses the immutable `github.event.pull_request.head.sha`, `persist-credentials: false`, `fetch-depth: 1`, and verifies `git rev-parse HEAD` afterwards;
- each test runs with the same JDK25 `verify-supervisor-escrow-runtime-bridge-v1.sh` script and strict source-hash/Surefire-count gates.

If the opt-in is true but the tuple is wrong, the test job is **skipped** and a separate `deny-unapproved-runner` job fails without checkout. The run must not be interpreted as passing due to a skipped job. A fail-closed denial may also fail before job execution if the source org has no hosted minutes; neither outcome is positive test evidence.

## Administrator-controlled preparation — **not actions performed by ChatGPT**

1. Independent security/code review of **exact** workflow head, workflow diff, source tree and branch policies. Require protected refs and trusted maintainers. Never enable on fork PRs, arbitrary workflow inputs or an unreviewed ref.
2. Provision a new disposable Linux x86_64 VM **at repository scope** with no persistent source volume and the labels `self-hosted`, `linux`, `x64`, `oreslang-private-ephemeral`. Prefer JIT single-use registration or `--ephemeral` plus fresh VM destruction after the job; no reused workspace, caches, application/cloud credentials or shared host. Do not save registration tokens in logs or source.
3. In the source repository's GitHub Actions **Variables**, set `ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA` to the **new exact reviewed head SHA**, then set `ORESLANG_PRIVATE_EPHEMERAL_RUNNER=true`. These are opt-in repository variables and NOT secrets; their values must not substitute for independent security review.
4. Rerun the matching PR workflow after the runner registers. If an approved SHA does not match, STOP: do not force checkout or weaken conditions to make it pass.
5. Demand *stepful* execution and attach full private report: exact HEAD and blob receipts, Java/Javac/Maven versions, 557 assertions, 65,536 DATA admissions with 90 extra checks, and 16 specified JUnit cases in four exact Surefire suites, with failures/errors/skips all zero.
6. Stop/destroy the ephemeral VM and revoke registration. Reset runner opt-in variable to `false`, remove or unset approved SHA, and verify no source/cache survives outside controlled private retention. Workflow artifacts/logs must not include private source or secrets. A successful self-hosted run does not itself certify Graal Native Image, guest AttoLang semantics, physical FFI stack cleanup, or five-backend parity.

**No mirror or source-copy operation is authorized by this document.** The workflow and related changes remain DRAFT until independently approved, real execution verified, and the existing release/security gates met.
