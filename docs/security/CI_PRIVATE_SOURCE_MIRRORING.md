# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo` destination through
an independently reviewed governance change.

An allowlist line is necessary but no longer sufficient. The reviewed
`.github/private-ci-carrier-contracts.json` must independently pin the live
destination repository ID, an exact reviewed source-free carrier `main`
commit, the carrier workflow blob, and the exact reviewed test-plan blob/PR
set. A carrier `main` advance, repository delete/recreate, workflow change,
or test-plan change fails closed until the source policy is reviewed again.

V3 supports two fail-closed origin-governance modes:

1. **Protected-main mode.** When GitHub reports `main protected: true`, the
   publisher reads branch-protection rules and requires CODEOWNER reviews,
   stale-review dismissal, at least one approval, administrator enforcement,
   no force pushes/deletions, and no PR-review bypass allowances.
2. **Exact review-attested mode.** On private-repository plans where GitHub does
   not provide branch protection/rulesets, V3 requires the designated
   authorization PR to be merged to source `main`, requires the latest review
   from immutable security reviewer ID `11139560` (`ORESoftware`) to be
   `APPROVED` on that PR's **exact current head**, requires that approval to
   have been submitted before merge, and compares every export-critical
   governance blob on current `main` against the independently reviewed PR
   head. Any stale/revoked review, later governance edit, reviewer mismatch,
   review-history ambiguity, or policy-byte mismatch fails closed.

The review-attested fallback is deliberately narrow: it does not make an
unprotected branch generally trustworthy. It only authorizes the exact
reviewed private-CI governance blobs, and V3 repeats the authorization check
immediately before creating the source-bearing and one-use authorization refs.
The legacy publisher remains protected-main-only.

At the 2026-10-20 audit GitHub still reported `main protected: false`, and
ruleset inspection returned a 403 requiring a paid/private-repository feature.
Therefore V3 must use the exact review-attested path for this repository unless
its GitHub plan changes. Source-free test suites remain permissible while the
independent review is pending.

Do **not** accept a CI destination allowlist from environment variables or
unreviewed workflow inputs: a caller must not be able to grant its own approval.
Run the checked transfer from trusted, reviewed source, not an untrusted PR
checkout. If an approval policy can be modified by the code under test, treat
the result as untrusted.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. V3 accepts an approved destination, a bounded local source
directory, the exact open source PR head, and one test plan already pinned by
the protected carrier contract:

```bash
scripts/run-in-private-ci-destination.sh opto-sync-test/go-java-validation \
  --source-dir ./verified-source-tree \
  --source-commit FULL_40_CHARACTER_OPEN_PR_HEAD_SHA \
  --source-pr 1482 \
  --test-plan ci/test-plans/java-pr1482.json
```

The caller does not choose the executable carrier branch. After proving the
complete source Git tree, the dispatcher derives exactly
`ci/oreslang-<SOURCE_ROOT_TREE_SHA>`. The exact source commit must also be
the current head of the named open PR against source `main`.

The checked-in policy file must explicitly list the destination. The
publisher checks the live GitHub identity and `visibility=private` /
`private=true` before sending any private bytes and rechecks visibility
immediately before each write. Every GitHub API write is constructed from
that **same validated owner/repo**, rather than a caller-provided URL, Git
remote, shell command or separate destination argument. It creates blobs,
then a **fresh standalone source tree with no `base_tree`**, followed by a
root tree that replaces the **entire** `private-oreslang-source/` directory
in the destination's default tree. This removes stale files from previous
snapshots while preserving unrelated destination workflows and files.
The v3 dispatcher creates a canonical `ores-private-ci-receipt-v3` that
binds source repository ID, PR number/head SHA/root-tree SHA, manifest SHA-256,
destination repository ID and reviewed base commit, immutable carrier workflow
blob, reviewed test-plan digest, and a 24-hour expiry. The receipt digest is
bound into the carrier commit message. It then creates a **new**
`ci/oreslang-<SOURCE_ROOT_TREE_SHA>` ref and the exact
`ci/authorized-<CARRIER_COMMIT_SHA>` dispatcher authorization ref required
by the carrier. Existing branches, including `main`, are never updated.

The reviewed carrier base must itself be source-free: neither
`private-oreslang-source/` nor `ci/oreslang-receipt.json` may already be
present. The source-bearing commit may change only that source subtree plus
the receipt; carrier workflow and test-plan bytes remain inherited from the
pinned base.

An explicitly supplied `--source-commit` now pins the **exact original
private Git commit**. Before uploading, the publisher retrieves that commit's
source-tree hash and its complete, non-truncated recursive tree from the
official origin. It rejects untracked/dirty/missing local files, differing
executable modes, symlinks/submodules, or an incomplete tree. Every local
path/mode/blob hash must match the origin tree exactly. After uploading,
the newly assembled isolated subtree must have the **same Git tree SHA**
as the verified original, before the private destination's root commit/ref
can be created. No transfer is authorized merely because a filesystem
directory has a trusted-looking name.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. It rejects symbolic links,
hardlinks to outside files, FIFOs, `.git` directories, and other unsafe
filesystem objects. Directory enumeration is bounded to 4,096 entries,
1,024 directories and a maximum depth of 64. It verifies every uploaded
Git blob SHA-1 against the source bytes. It checks that the reviewed
destination policy has not changed
before every API write, in addition to rechecking the destination's live
private visibility. It does not print source contents in API failures.
API failure before ref creation can leave unreferenced Git objects in
the private destination; a follow-up retention policy is still needed.

It does **not** intercept arbitrary connector/API tool writes, cannot
completely prevent visibility changes between API checks, and cannot
guarantee that the source directory itself is free of secrets. Run only from a checkout whose private-CI governance matches the exact
protected-main or review-attested authority accepted by V3, using narrowly
scoped credentials.
There is no permission to use a public repository when CI minutes run out.

Test without network access or sending any source:

```bash
bash scripts/test-private-ci-destination.sh
python3 scripts/test-publish-private-ci-source.py
python3 scripts/test-publish-private-ci-source-v3.py
```

The Bash test uses a temporary policy checkout; Python tests mock all GitHub
API operations. V3 tests additionally prove protected carrier-contract parsing,
source-free base enforcement, exact open-PR binding, receipt construction,
tree-derived branch naming, and dispatcher-authorization ref creation. The
real allowlist remains empty and **no destination is authorized** unless
separately reviewed and protected-main enforcement is active.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
