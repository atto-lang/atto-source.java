# NIO / actor exact-head CI recovery (source-local only)

Status: **BLOCKED — no executed compiler/runtime CI steps as of 2026-10-18**.
This document is an operator handoff, not permission to export private source,
proof of a successful compiler build, or a reason to merge the draft PRs.

## Independently pin exact heads and trees before qualification

Source repositories: `oreslang-source.java` PR #1396 and
`oreslang-source.llvm` PR #638, both in `ores-truffle-oreslang`.
The audit observed Java head `5c4dbf3b2ff34e58dfd979770a04ba4b8163b162`
before this documentation was committed, and LLVM head
`4e4a7c8b6fa15d59bb36fb8c553798056dc05b15`. **These are historical
snapshots, not live run parameters.** Every new commit changes the head and
possibly its tree.

Before running anything, an administrator must obtain the **current** PR head
SHA and corresponding Git tree SHA from an independently reviewed GitHub
record. Provide both via `APPROVED_JAVA_HEAD`/`APPROVED_JAVA_TREE` or
`APPROVED_LLVM_HEAD`/`APPROVED_LLVM_TREE`; never silently substitute local
`HEAD` for an independently approved value. Require exact equality before
the build and again in the source-local validation gate.

## Runner failure observations (not a confirmed root cause)

Java Actions [full run #39176301519](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39176301519):
jobs `test`, `native-aot`, `native-hybrid`, `polyglot-isolate` all
failed in about two seconds with `runner_id=0`, empty `runner_name`,
`runs-on: ubuntu-latest`, and **zero steps**. Fast run
[#39176301490](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39176301490)
also has zero steps. LLVM [compiler run #39176959054](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/actions/runs/39176959054)
failed before runner assignment, using `ubuntu-24.04`. Job log retrieval returned
an unavailable blob. Check-run annotation counts exist, but the connected API
does not grant their text, so billing exhaustion is **not verified**.

Administrator checklist in the **source organization**:
1. Open the failing job in GitHub UI; collect the exact **job annotation/error**
   rather than guessing at the cause.
2. Review Actions billing/usage, spending limits and current payment status.
   Review repository/organization allowed-actions policies and runner-group
   eligibility; verify whether GitHub-hosted runners are enabled.
3. If the problem is budget, restore the source organization's allowance.
   Otherwise use a reviewed, ephemeral, **same-source-repository** self-hosted
   runner. No need to export source to another GitHub organization.
4. On an approved isolated host, give the runner only read access to the
   private source checkout; no production credentials, privileged services,
   or persistent shared Maven/Gradle caches. Review the exact commit before
   allowing a PR's Maven/CTest execution, since build scripts execute code.
   Destroy the disposable workspace/VM afterward.
5. Record job IDs, assigned runner IDs, nonzero executed step counts, SHA/tree,
   verified build/test counts and logs before calling the gate green.

Do **not** blanket-change a pull-request workflow to run on an unprotected
privileged self-hosted runner. Use manual approval / a restricted environment
and trusted source provenance for any privileged runner.

## Java: approved source-local execution

Prerequisites: clean authorized checkout of PR #1396 head above, **GraalVM
JDK 25** with `native-image`, Maven, Python 3, Bash, Git; network access only
for approved build dependencies. The gate verifies exact HEAD/tree,
raw tracked bytes, Maven test XML and source checkout consistency.

```bash
: "${APPROVED_JAVA_HEAD:?Set the independently reviewed PR head SHA}"
: "${APPROVED_JAVA_TREE:?Set the independently reviewed Git tree SHA}"
test "$(git rev-parse --verify HEAD^{commit})" = "$APPROVED_JAVA_HEAD"
test "$(git rev-parse --verify HEAD^{tree})" = "$APPROVED_JAVA_TREE"
bash scripts/test-nio-exact-tree-source-local.sh \
  "$APPROVED_JAVA_HEAD" "$APPROVED_JAVA_TREE" --native-aot
```

Then execute the existing full `ci.yml` profile (JVM, Native Image AOT,
hybrid host and polyglot isolate), not just the source-free checker. Require
real `Future<T>`/guest `await`, two-listener I/O isolation, actor admission,
cancel/close/shutdown races and non-skipped JUnit evidence.

The unsigned helper receipt lives in `target/nio-exact-tree-gate/receipt.txt`
alongside phase logs and preserved targeted Surefire reports. Review it **with**
trusted execution logs, not as a cryptographic attestation.

## LLVM: approved source-local execution

Prerequisites: separate authorized clean checkout of PR #638 at the head
above, GCC, Clang, ASan/UBSan runtimes, CMake, CTest and matching LLVM dev
dependencies. Run both compiler CI and the native NIO lifecycle sanitizer gate.

```bash
: "${APPROVED_LLVM_HEAD:?Set the independently reviewed PR head SHA}"
: "${APPROVED_LLVM_TREE:?Set the independently reviewed Git tree SHA}"
test "$(git rev-parse --verify HEAD^{commit})" = "$APPROVED_LLVM_HEAD"
test "$(git rev-parse --verify HEAD^{tree})" = "$APPROVED_LLVM_TREE"
export ORES_NIO_EXPECT_HEAD="$APPROVED_LLVM_HEAD"
export ORES_NIO_EXPECT_TREE="$APPROVED_LLVM_TREE"
bash scripts/test-native-vm-nio-host-sanitizers.sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=ON
cmake --build build --parallel 2
python3 -B tests/ctest_manifest_gate.py --build-dir build --min-tests 250
ctest --test-dir build --output-on-failure --timeout 240 --parallel 2
```

Build-directory creation is permitted; source integrity checks must still
pass before and after execution. Require actual guest program/compiler
evidence before claiming `Future<T>`/await parity. Host epoll tests alone
are not enough. Additional actor race/ASan/UBSan tests are in the
existing `.github/workflows/ci.yml` and sanitizer scripts.

## Privacy and release hold

[Platform security issue #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22)
and [issue #77](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77)
remain open. A private destination, sufficient Actions minutes and identical
Git tree are **not** substitutes for the required independent source/destination
approval and incident closure. Do not send the source tree, blobs, build
artifacts, or embedded source-bearing fixtures into another organization.

Both integration PRs remain **DRAFT / DO NOT MERGE** pending independently
reviewed, real executed CI results and explicit security signoff.
