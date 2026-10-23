# NIO/Future-to-await source-local qualification gate

Status: **draft verification recipe**, not a CI run and not release approval.

## Why this gate exists

The source organization currently reports GitHub Actions failures **before any
runner was allocated or build/test step executed**. A red zero-step Actions job
does **not** establish that JDK, Maven, the parser, or the actor runtime failed.

The repo-local helper `scripts/test-nio-exact-tree-source-local.sh` permits a
trusted reviewer or authorized source-repository runner to qualify one immutable
source snapshot without copying private Git objects to another organization.

This document **does not** grant access to a self-hosted runner, turn on a
workflow, change branch protection, or authorize a private-code mirror.

## Preconditions

1. A source-org administrator has approved and provisioned a disposable,
   repository-scoped Linux runner, **or** a reviewer has access to an otherwise
   authorized source-local machine. Do not execute unreviewed PR code on a
   privileged runner or with ambient secrets.
2. An independent reviewer records the proposed complete **40-hex commit SHA**
   and **40-hex Git tree SHA** from the current integration PR head. Pin both
   *before* checkout. Do not derive the expected values from the checkout
   being qualified.
3. The runner checks out exactly that commit **from the same private source
   repository**, with no cross-org Git object transfer and no persistent
   checkout credentials. The tracked/untracked checkout is clean.
4. JDK **25**, matching `javac` and Maven using JDK 25, plus `python3`
   for parsing actual JUnit XML evidence, are installed.
   Optionally install a GraalVM `native-image` toolchain for the native-aot
   package step. This helper is a runner command, not a runner provisioning
   script.

## Source-free gate verification

Before asking an approved runner to execute private guest sources, reviewers can
exercise the qualification script's own preflight and receipt policy using
disposable **local** Git repositories and simulated `java`, `javac`, and
`mvn` executables:

```bash
python3 -m unittest discover -s tests -p test_nio_source_local_gate.py -v
```

The source-free test methods cover exact commit/tree mismatches, dirty checkouts,
unsafe `target/` and receipt symlinks, a clean pinned checkout with a
simulated passing toolchain, and propagation of a simulated Maven failure
through `tee` and the receipt. This is a **source-free simulation**, not real
JDK25/Maven/JUnit qualification. Passing it does not satisfy the source-head
Actions execution gate.

## Execute

```bash
# Values must come from the independent reviewed source PR record.
REVIEWED_COMMIT='<exact 40-hex integration PR head commit>'
REVIEWED_TREE='<exact 40-hex git tree>'
git rev-parse HEAD
git rev-parse 'HEAD^{tree}'

# Targeted JUnit tests, then complete Maven verify.
bash scripts/test-nio-exact-tree-source-local.sh \
  "$REVIEWED_COMMIT" "$REVIEWED_TREE"

# On an authorized GraalVM runner, additionally build Native Image:
bash scripts/test-nio-exact-tree-source-local.sh \
  "$REVIEWED_COMMIT" "$REVIEWED_TREE" --native-aot
```

The script rejects mismatched commit/tree IDs, dirty or untracked checkout,
any nonstandard Git index tag (including **assume-unchanged** and
**skip-worktree**), unsafe `target/`, evidence-directory or receipt symlinks,
missing tools, the wrong Java/javac/Maven JVM version, and failed build steps.
It uses `pipefail` so `tee` never converts a Maven failure into a pass.
Checking only `git status` is insufficient: Git may deliberately hide
modified tracked bytes behind those index flags while HEAD/tree SHA remain
unchanged. Both flags are rejected on preflight and postflight.
A local `core.fileMode=false` Git setting can similarly conceal a changed tracked
executable bit while the commit/tree IDs stay fixed. The gate overrides the
checkout configuration with `core.fileMode=true` and disables filesystem
monitor caching during both identity checks. A source-free regression
reproduced the original false-positive PASS, and the hardened script now
rejects the same attack with exit 67 before Maven or exit 70 after Maven.

**Ignored-file fence:** Ordinary `git status` does not report ignored files,
including an untracked `.mvn/maven.config` deliberately concealed through
`.git/info/exclude`. That file can change Maven flags without changing Git
commit/tree identity, including introducing `-DskipTests`. The gate therefore
rejects any ignored non-build files before running Maven and after testing
(exit 74). Only ignored paths under the root `target/` build-output directory
are permitted; the initial `mvn clean` must remove that directory. This
strict policy may require clearing developer-local ignored files before a
source-local qualification. It does not make the unsigned receipt tamperproof.

**Clean-build fence:** Even an otherwise clean Git checkout may contain ignored
`target/` compiler outputs. After checking source identity and JDK 25, the
helper runs `mvn -B -ntp clean` **before** any targeted JUnit command.
It then **requires `target/` to be absent**, failing with exit code 71 if a
Maven clean configuration silently skips or incompletely deletes the old build.
Only then does it recreate the evidence directory. This prevents stale
`target/classes` or `target/test-classes` from supplying the test result.
A Maven clean failure is itself fatal and still produces a failure receipt.
The gate does not delete private checkout content outside the Maven build output.

Its first test command runs:

```bash
mvn -B -ntp -Dores.runtime.carriers=native \
  '-Dtest=VmNioTcpListenerTest,NativeNetStdlibTest,NioReadinessGroupTest,NativeNioFutureReactorTest,OresSchedulerTest,OresCarrierPermitPoolTest,OresFuturesTest,OresFutureCallbackTest' test
```

This includes deterministic listener **and connection** descriptor-close
failure regressions, guest dual-listener Future-to-`await` coverage, the
four-socket 64-KiB partial-write/rearm integrity test, and native reactor /
Future callback regressions. Each Maven phase must produce **fresh Surefire XML** newer than its own
pre-execution marker; `scripts/nio_verify_surefire_evidence.py` requires
non-skipped testcases and checks all eight named targeted suites, including
for the subsequent full `verify` phase. Targeted XML is moved into
`target/nio-exact-tree-gate/targeted-surefire-reports/` before full Maven verify:
this prevents even deliberately future-dated targeted XML from proving full
verify executed tests when Maven silently skips them. Full verify must create
its own reports in the newly empty `target/surefire-reports/`.
Maven exit code zero without a new
JUnit report, missing test classes, malformed XML, or skipped-only suites
fails the source-local gate (exit 76). The report parser also rejects symlinked
reports, contradictory `tests`/`skipped`/`failures`/`errors` counters, and
reports whose filename impersonates a mandatory suite without an executed
testcase of that class. Run source-free adversarial verifier checks with
`python3 -m unittest discover -s scripts -p test_nio_evidence_verifier.py -v`.
The targeted test invocation explicitly disables Maven test-skipping flags.
On filesystems with coarse timestamp granularity a freshly written JUnit XML
report may have precisely the same `mtime` as its phase-start marker.
The verifier therefore accepts report timestamps **at or after** the marker,
while the shell gate preserves the essential freshness boundary: `mvn clean`
must remove all previous `target/` contents before the targeted phase, and
all targeted reports are moved out of `target/surefire-reports/` before the
full-verify phase. Report times *older* than the marker still fail. This
corrected two reproducible false negatives in the 17-method source-free
shell gate suite and is covered by a dedicated equal-timestamp regression.

The full suite has not been executed
on a real runner; this is an evidence safeguard, not certification. It then
runs `mvn -B -ntp -Dores.runtime.carriers=native verify`. An explicitly
requested `--native-aot` also runs
`mvn -B -ntp -Pnative-aot -DskipTests package`.

## Source-free self-tests of the qualification gate

These use temporary synthetic Git repositories and mocked Java/Maven; they
never access or export private Oreslang sources and **are not runtime tests**:

```bash
python3 -m unittest discover -s tests -p 'test_nio_source_local_gate.py' -v
python3 -m unittest discover -s tests -p 'test_nio_gate_git_inspection.py' -v
python3 -m unittest discover -s tests -p 'test_nio_surefire_evidence.py' -v
python3 scripts/test-nio-exact-tree-source-local-selftest.py
```

The disposable test repositories now copy the **exact checked-in Surefire verifier**
as a tracked source file, so mocked Maven success cannot pass by relying on
an omitted test helper. The dedicated XML suite rejects suite-name identity
substitution, contradictory counters, symlinks and entity declarations. XML
parsing is capped at 32 MiB per report to reduce unbounded-memory exposure.
The checker accepts only UTF-8 XML (optionally with a UTF-8 BOM) and applies
DOCTYPE/ENTITY restrictions to decoded text: multi-byte UTF-16/UTF-32
declarations must not bypass the XML policy. A source-free regression
reproduced the previous UTF-16 entity-policy bypass; the stricter checker
rejects it without treating synthetic evidence as JUnit compilation.
The verifier also requires a nonblank `testcase@name` for every executed test,
and rejects failure/error elements anywhere in the XML tree (including those
hidden outside `<testcase>` elements with contradictory zero-error counters).
Four new source-free negative tests cover these malformed evidence claims.
When a `<testsuites>` wrapper declares aggregate counters, the verifier
cross-checks them against the child suites; an outer failure/skip cannot be
hidden by clean child reports. This was previously an incorrect source-free
PASS, and now has both rejection and acceptance regression cases.
These tests are source-free: no JDK25, JUnit, GraalVM or LLVM qualification.

The regressions cover wrong commit/tree, hidden tracked source changes using
`git update-index --assume-unchanged` and `--skip-worktree`, skipped-clean
stale classes, symlinked evidence paths, locally disabled Git file-mode
tracking, toolchain mismatch, interrupted Maven phases and the successful
optional native-image invocation. The gate refuses
these unsafe cases before it can claim a JUnit or exact-tree test result.

### Git inspection errors must fail closed

Git returning a nonzero exit from `status --porcelain` or the ignored-file
`ls-files` scan is **not** an empty clean checkout. Previously, those exit
codes were swallowed by command substitutions inside conditional tests: a
synthetic failing Git wrapper could still produce `result=PASS`. The gate
now captures both scans separately before inspecting output, and exits 75
on Git inspection errors in preflight or postflight. The dedicated
`tests/test_nio_gate_git_inspection.py` runs four error-injection regressions
plus one clean synthetic control (5/5 in the local pre-commit check). These
remain synthetic tests; they do not compile private Oreslang runtime source.

### Additional source-local integrity fences (2026-10-18)

A failure in `git ls-files -v` during postflight index inspection previously
returned a blank command substitution, causing the gate to incorrectly pass
an uninspected source tree. The gate now checks **Git index inspection exit
statuses** during preflight and postflight and exits 75 on inspection errors,
just as it does for porcelain status and ignored-file checks.

The receipt writer now uses a temporary private inode followed by an atomic
same-directory replacement; writing directly to an existing `receipt.txt`
could previously overwrite an outside file through a **hardlink**, even with
symlink checks enabled. Two new index-failure regressions and a hardlink
regression reproduce those bypasses on the old gate and pass on the fix.
The local source-free test suites now comprise 18 gate cases, 7 Git failure
cases, 20 Surefire cases and 10 other verifier cases (55/55 passing),
with all 13 standalone gate scenarios also executed in isolated repos.
None of this constitutes JDK25/GraalVM/LLVM compiler execution.

### Non-executing Git source qualification

Untrusted `git status` may launch a repository-configured
`filter.*.clean` program; a clean checkout assertion must not execute those
programs. The source-local gate now uses an independent raw-byte file
verification plus untracked/ignored scans via `git ls-files` instead of
`git status`. The raw verifier separately cross-checks every staged index
mode/object ID against recursive `HEAD` tree metadata, rejecting staged
but uncommitted changes without invoking filters. A source-free negative
test configures a hostile clean filter with a side-effect marker, changes
source bytes, and proves the gate refuses admission without creating that
marker. Tracked content/mode mutations are reported with exit 77 and the
standalone synthetic scenario expectations were updated accordingly.
Local checks: 20 source gate, 7 Git-failure, 20 Surefire and 10 additional
verifier tests, plus 13 standalone scenarios all passed. These are
source-free qualification tests, not compiler or runtime CI.

### Raw Git blob identity beyond porcelain status

A local untracked `.git/info/attributes` rule can configure a clean filter
that makes `git status` appear clean while actual source bytes differ from
the pinned Git tree. The source-local gate now verifies **raw tracked bytes**
against staged SHA-1 Git blobs independently at admission and postflight,
checks file modes and symlink target bytes, denies Git index/worktree override
environment variables and checks its own verifier's unfiltered Git blob
before invoking it. Any raw mismatch exits **77**. An adversarial fixture
demonstrated a synthetic source edit masked by `tr A-Z a-z` being accepted
on the previous gate and rejected after hardening. The existing source-free
test fixtures include the exact tracked raw verifier helper. The Python
unittest suites pass 19 + 7 + 20 + 10 tests; the 13 standalone scenarios
also pass in separate disposable repositories. These are not evidence of
real Java/JDK25/GraalVM/LLVM compilation or test success.

### Verify the verifier against HEAD before executing it

The pre-execution source verifier must itself be trusted before it is invoked.
Previously the shell gate compared the raw Python checker against the
**staged index** object and launched the Python file. A source-free adversarial
case proved that an altered checker staged with `git add` could execute code
**before** its subsequent HEAD/index consistency check rejected the tree
(exit 77 came too late). The qualification helper now requires BOTH the
staged blob and unfiltered raw checker blob to equal the already pinned
`HEAD:scripts/nio_verify_raw_worktree.py` blob before Python execution.
The new regression writes a side-effect marker from unapproved staged Python
code, proves it executed under the previous gate, and checks that the
hardened gate rejects it without any marker or Maven start.

Local source-free qualification: 21 gate unittests, 7 Git-inspection tests,
20 Surefire tests, 10 other verifier tests (58/58). The 13 standalone
synthetic cases passed across two groups; an eight-worker harness invocation
exceeded its 15-second per-process timeout for four cases, which were
re-executed successfully with a 60-second timeout and two workers.
These tests are not actual JDK25 Maven/JUnit/GraalVM or LLVM compiler tests.

### Eliminate the raw-verifier check/use race (2026-10-18)

The gate originally verified the raw Python helper's worktree hash and then
launched the helper **by pathname**. A concurrent swap after `git hash-object`
but before `python3` could execute unapproved Python from the worktree.
A deterministic synthetic Git shim reproduces this: the old gate eventually
returned exit 77 but had **already created the injected side-effect marker**.
The source-local gate now loads the approved verifier code by immutable Git
blob ID (`git cat-file blob "$approved_blob"`) and executes those bytes via
isolated Python `-I -B -c`, preserving the same commit/tree arguments. The
mutable worktree helper is still hashed for mismatch detection, but is no
longer the executed Python source.

The new regression asserts **no side effect and no Maven start**. Locally
executed synthetic results: 22/22 exact-tree gate tests, 7/7 Git-inspection
checks, 20/20 Surefire evidence cases, 10/10 additional verifier cases and
13/13 standalone scenarios. These do not prove full JDK 25/Maven/GraalVM or
LLVM compiler correctness. A trusted bootstrap and reviewed shell entrypoint
remain prerequisites; this change addresses only the verifier path race.

## Evidence, trust boundary and interpretation

- The script writes an **unsigned** `target/nio-exact-tree-gate/receipt.txt`
  (expected/observed SHA and tree, tool versions, timestamps, final stage,
  exit result) and Maven logs within that private checkout. Standard Maven
  test reports are under `target/surefire-reports`.
- The receipt is not tamperproof evidence or a substitute for independently
  verified GitHub runner assignment, real Actions job **steps**, and associated
  compiler/test results. Store source-private logs only in appropriately
  restricted evidence storage.
- A passing Java gate does not prove LLVM guest Future suspension. The native
  LLVM compiler still rejects unsupported `Future<T>` continuation lowering.
- No release merge follows solely from the source-local helper; require code
  review, successful compiler/JUnit and native integration tests, verified
  commit/tree identity, and resolution of all blockers.

## Cross-organization mirror policy

The existence of budget/minutes in another GitHub organization is **not**
authorization to export private source. Exact-tree comparison proves identity,
not approval. Until protected independent source/destination/snapshot approval
is enabled and security incidents
[platform-conformance #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
and
[#77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77)
are resolved, **do not mirror private repository blobs or trees across
organizations**.

Track approved-runner provisioning separately from the language/runtime PR:
[source runner blocker #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963)
and
[NIO integration tracker #1264](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1264).
