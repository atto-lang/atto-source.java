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
4. JDK **25**, matching `javac` and Maven using JDK 25, are installed.
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

The six test methods cover exact commit/tree mismatches, dirty checkouts,
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
a `target/` symlink, missing tools, the wrong Java/javac/Maven JVM version,
and failed build steps. It uses `pipefail` so `tee` never converts a Maven
failure into a pass.

**Clean-build fence:** Even an otherwise clean Git checkout may contain ignored
`target/` compiler outputs. After checking source identity and JDK 25, the
helper runs `mvn -B -ntp clean` **before** any targeted JUnit command and
recreates the evidence directory afterward. This prevents a prior stale
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
Future callback regressions. It then
runs `mvn -B -ntp -Dores.runtime.carriers=native verify`. An explicitly
requested `--native-aot` also runs
`mvn -B -ntp -Pnative-aot -DskipTests package`.

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
