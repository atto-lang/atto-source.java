#!/usr/bin/env python3
"""Review-only four-PR integration closure: pin each source file to its PR HEAD blob.

Executed only inside the original private source repository; not an exporter,
review quorum, source-main branch-protection authority, or merge permission.
If any PR head changes, regenerate/re-review this immutable inventory first.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

PR_HEADS = {
  "51": "1bdfa3cc7819beb75357e44a6f92d2b7cd66f066",
  "52": "cab9dc58455cfa6e1aeb6cfc52ef4fdb39fd142e",
  "53": "f6070c014698a36705fd26e46190d075c397ed11",
  "58": "167eb5cda0c4e4cf523b336cdb252c74377e6d97"
}
EXPECTED_BLOBS = {
  "51": {
    ".github/workflows/java-functional-pipelines.yml": "b8d3884064f2d32092bfb8efc79eb55c3780166d",
    "ci/compiler/verify-functional-pipeline-edges.sh": "925a21d548b05ebe6a5b745000db1fe4572a1836",
    "ci/compiler/verify-functional-pipelines.sh": "7e558498db75cf1bce0bdd8fd492643f954c1458",
    "docs/functional-pipelines-guest-v1.md": "e1a5767f5fe134467c3b8e74031c7ecd972f2440",
    "fixtures/pipelines/block-body-83.atto": "4a2eab34e1ba39709198afda19a71cfce67f4030",
    "fixtures/pipelines/edge/checked-overflow.atto": "59bb890b54addc1659065ab55cd565771523bfce",
    "fixtures/pipelines/edge/preapplied-grouped-42.atto": "9a89ada18865fead0d92a7de75415957fbb5f67f",
    "fixtures/pipelines/edge/reject-i64-negative-underflow.atto": "8b63b45e406db3e19aa793e0f7a627914623b9e0",
    "fixtures/pipelines/edge/reject-i64-positive-overflow.atto": "2e584ee01ee74a3a31e282cf0116957f9ff95bd1",
    "fixtures/pipelines/edge/reject-reserved-unsafe.atto": "b159def3fa26ca6023395e1c546b95c1b929fd14",
    "fixtures/pipelines/edge/signed-i64-min.atto": "bbc22347d7ccb0a5be1a7c2193f11c5f2f4805cc",
    "fixtures/pipelines/edge/two-argument-61.atto": "4089c524db1d076593994ad65acb5ca7f90eea32",
    "fixtures/pipelines/grouped-42.atto": "c1d4e5522bae974c3bed98156ca7e0e3bf1ea573",
    "fixtures/pipelines/inline-arrow-83.atto": "ec25b1ce607afa7d30dd5315eace12ff42f0c4d6",
    "fixtures/pipelines/negative/arity.atto": "8c005cb183e9af26789017c7e20648b590927009",
    "fixtures/pipelines/negative/arrow-brace-no-return.atto": "a966bd749c3cf68a686fe069571613c32d9ed9b6",
    "fixtures/pipelines/negative/bad-callee.atto": "d58ebc28a0cb4f5caf95a14e0f4e2b8f8d8e0ac3",
    "fixtures/pipelines/negative/duplicate-local.atto": "a88bdccce88710845e5e7f25a97c7d0ce3300999",
    "fixtures/pipelines/negative/forward-local.atto": "fa546269aa08624f84f12a70b6399f323ae442cb",
    "fixtures/pipelines/negative/missing-semicolon.atto": "417e3bac708545dfb4145f819d778acb9d74b07f",
    "fixtures/pipelines/negative/mixed-forward-reverse.atto": "c04cf6b95742064024ff67ecbccd6416d250c2df",
    "fixtures/pipelines/negative/mixed-reverse-forward.atto": "654cf1d7bbd0c30a152c6619d155ba2669c117b0",
    "fixtures/pipelines/negative/signed-int64-underflow.atto": "5c52861ff94b1771970cb4d57cb8be00a4443ef5",
    "fixtures/pipelines/negative/unknown-call.atto": "c6202a4cf697da03ccf1054370809a212e6b20d3",
    "fixtures/pipelines/negative/wrong-type.atto": "23b86d7117d7994c1de674dadaec9742a55a6ba5",
    "fixtures/pipelines/reverse-42.atto": "592e6b206e0a758f8e418d4915df5555ca415d10",
    "fixtures/pipelines/signed-int64-min.atto": "52b2880b835af88f2a0376b9ae154adc23b24e34",
    "src/compiler/pipeline/AttoFunctionalPipelineCompiler.java": "a0846be581f0e1124bdd339bc40564f1be5e9670"
  },
  "52": {
    ".github/workflows/int-parse-result-host.yml": "3f42fe8a028a77ddfffb1dbd346bad410fcd654c",
    "ci/compiler/verify-int-parse-result.sh": "d129b484078308c3bf8117e38e84b28fb677182f",
    "docs/int-parse-result-host-v1.md": "ce71b069743b67075db09876d5740d694915f014",
    "src/compiler/adt/AttoIntParseRuntime.java": "ec00512c0aa2b81cc25da61d34440b0d1b37e865",
    "tests/compiler/AttoIntParseRuntimeProbe.java": "1708a7efa801fa1ce740e0375b12781c1cd6379e"
  },
  "53": {
    "ci/compiler/verify-channel-read.sh": "fd0a1ea06cc62163689219fffc189cf8d16865a4",
    "src/compiler/channel/AttoChannelReadPlan.java": "fca13c890b1d213f056fa65ca5c6f4c707b228e4",
    "tests/compiler/AttoChannelReadPlanProbe.java": "d0ae765943222f79772bfa88e4f36ba4b0bc9667"
  },
  "58": {
    "ci/compiler/verify-m1-java.sh": "5bb3861b0f1d74bd2e95ab9238daeb44e7dc7c83",
    "fixtures/mutex_constructor/negative_bare.atto": "f362142fa7a3390fc06ba4845f2b3265df3fb206",
    "fixtures/mutex_constructor/negative_new_unsupported.atto": "dd94f0960ef815027c559bee2adb23a8cbc2ee60",
    "src/compiler/m1/AttoObjectM1JavaCompiler.java": "c80bbf53032bd4a7361edc9179dd061d2dc65199",
    "tests/compiler/AttoMutexConstructorM1Probe.java": "8a8c423c94a1d1e4c09055354007d58fc1f64eae"
  }
}

# Git modes are part of the reviewed source tree, not cosmetic metadata.
# All 41 PR files are 100644 except the one recorded executable script in #52.
# This whitelist is pinned to the exact PR heads above, not inferred from HEAD.
EXPECTED_EXECUTABLE_FILES = {
    "51": set(),
    "52": {"ci/compiler/verify-int-parse-result.sh"},
    "53": set(),
    "58": set(),
}

def git(*args: str) -> str:
    p = subprocess.run(["git", *args], check=True, text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout.strip()

def verify() -> None:
    root = pathlib.Path.cwd()
    if not (root / ".git").exists() and not (root / ".git").is_file():
        raise RuntimeError("must run in an exact source checkout")
    if git("status", "--porcelain", "--untracked-files=all"):
        raise RuntimeError("nonclean checkout: unexpected tracked/untracked source")
    commit = git("rev-parse", "HEAD")
    tree = git("rev-parse", "HEAD^{tree}")
    print(f"ATTO_FOURWAY_SOURCE_HEAD={commit}")
    print(f"ATTO_FOURWAY_SOURCE_TREE={tree}")
    if set(PR_HEADS) != set(EXPECTED_EXECUTABLE_FILES):
        raise RuntimeError("Git mode allowlist must match the four exact PR heads")
    if len(PR_HEADS) != 4 or set(PR_HEADS) != set(EXPECTED_BLOBS):
        raise RuntimeError("four-PR closure inventory is incomplete")
    all_names = set()
    count = 0
    for pr in ("51", "52", "53", "58"):
        head = PR_HEADS[pr]
        entries = EXPECTED_BLOBS[pr]
        if not re.fullmatch(r"[0-9a-f]{40}", head) or not entries:
            raise RuntimeError(f"invalid PR head/empty entries: {pr}")
        if not EXPECTED_EXECUTABLE_FILES[pr].issubset(entries):
            raise RuntimeError(f"reviewed executable path not present in PR #{pr} file inventory")
        print(f"ATTO_FOURWAY_PINNED_PR={pr}:{head}")
        for name, expected in sorted(entries.items()):
            posix = pathlib.PurePosixPath(name)
            if (name in all_names or posix.is_absolute() or ".." in posix.parts
                    or "\\" in name or not re.fullmatch(r"[0-9a-f]{40}", expected)):
                raise RuntimeError(f"duplicate/invalid tracked path or SHA: {name}")
            all_names.add(name)
            path = root.joinpath(*posix.parts)
            if not path.is_file() or path.is_symlink():
                raise RuntimeError(f"missing/nonregular/symlink source file: {name}")
            entry = git("ls-tree", "HEAD", "--", name)
            if not entry or "\t" not in entry:
                raise RuntimeError(f"missing Git tree entry: {name}")
            left, actual_name = entry.split("\t", 1)
            parts = left.split()
            expected_mode = "100755" if name in EXPECTED_EXECUTABLE_FILES[pr] else "100644"
            if actual_name != name or len(parts) != 3 or parts[0] != expected_mode or parts[1] != "blob":
                raise RuntimeError(f"Git mode/type/path drift: {name}; expected_mode={expected_mode}; git_entry={left}")
            tree_sha = parts[2]
            worktree_sha = git("hash-object", "--no-filters", "--", name)
            if tree_sha != expected or worktree_sha != expected:
                raise RuntimeError(f"PR#{pr} blob drift: {name}; expected={expected} tree={tree_sha} worktree={worktree_sha}")
            count += 1
        print(f"ATTO_FOURWAY_PR_BLOBS_VERIFIED={pr}:{len(entries)}")
    if count != 41:
        raise RuntimeError(f"unexpected pinned source file count: {count}")
    print(f"ATTO_FOURWAY_41_EXACT_PR_BLOBS=PASS count={count}")
    print("ATTO_FOURWAY_CI_EXECUTION=NOT_PROVEN_BY_SOURCE_HASHES")

if __name__ == "__main__":
    try:
        verify()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"ATTO_FOURWAY_BLOB_PIN_FAILURE={exc}", file=sys.stderr)
        sys.exit(1)
