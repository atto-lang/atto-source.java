# Private AttoLang `with` full-tree verification gate (proposal)

Status: **source-free synthetic gate PASS only**, NOT AUTHORIZED to transfer
private source, and NOT a full-source build. This PR adds a data-only Git tree
integrity check. It does not modify destination approval, carrier contracts,
source export policy, or release gates.

## Why full-tree CI remains blocked

On the proposed source PR #2832 head
`5341d59df54085dfa8b5b9ddba544875d88ab43a`, GitHub reported
**1,910 tracked blob entries**. The verified private alternate subset ran
on 348 selected files, not 1,910.

The existing source publisher `scripts/publish-private-ci-source.py`
currently sets `MAX_FILES = 1024`, and the approved V3 dispatcher invokes
the legacy bounded collector. Therefore a full 1,910-blob mirror exceeds
the publisher's hard bound even if a destination is approved. Do not bypass
the cap via manual connector writes or silently split the tree. A separately
reviewed, bounded policy change or independently attested segmented plan
is necessary.

The destination policy `.github/private-ci-approved-repositories.txt`
requires an independently approved exact owner/repository ID and
`@ORESoftware` review. A private repository having available Actions minutes
**does not grant source-transfer authority**. The V3 carrier contract pins
destination repository ID, base commit, workflow blob SHA and test-plan SHA,
and the publisher requires exact PR-head and independent review. The new
verifier must run only *after* those authorization checks pass.

## Proposed approved-carrier sequence

1. Independent reviewer approves destination ID, pinned workflow, exact
   source PR head, and a bounded way to support 1,910 source blobs.
2. Use the existing approved V3 publisher and receipt process, not
   arbitrary `GITHUB_TOKEN` cross-org checkout or unreviewed ad-hoc copying.
3. The publisher places the source Git tree under its existing
   `private-oreslang-source/` directory and writes the V3 receipt.
4. Before building, a trusted carrier workflow must independently pin
   expected **Git source TREE SHA**, expected **tracked blob count**,
   approved carrier and reviewed V3 receipt. It may then run:

   ```sh
   bash ci/verify-exact-with-tree.sh "$PINNED_SOURCE_TREE_SHA" "$PINNED_BLOB_COUNT"
   ```

   The script must run in the carrier root, not in the subtree. It checks
   exact subtree SHA (which commits paths, modes, symlinks and blob SHAs),
   complete blob count, nonsparse checkout and a clean worktree, and
   **fails closed** on a missing, partial, altered or extra-file mirror.
   It does not itself authorize destination, source PR, or receipt.
5. With only read-only permissions and no inherited secrets, run the full
   compiler JUnit suite and actual `.atto` fixtures. Record exact source
   HEAD, tree and test results separately from synthetic verification.
6. Require a separate GraalVM Native Image build/execution and finally
   backend-specific LLVM/.NET/Wasm/Forge conformance.

## Evidence and limitations

- [No-source private-carrier synthetic CI](https://github.com/voxletra/voxletra-e2e/actions/runs/42966487656):
  **PASS**, positive/negative Git tree mutations and symlink case.
  This repository run copied **zero private source files**.
- A locally constructed synthetic Git fixture likewise passed the
  standalone verifier's negative controls.
- **NOT EXECUTED:** a full 1,910-source-blob export, complete Java test
  suite, GraalVM Native Image, cross-backend tests, independent approved
  release promotion. All such claims require fresh exact-source receipts.
- No new destination is approved, no CI cap is increased, and no source
  branch has been merged as a result of this proposal.
