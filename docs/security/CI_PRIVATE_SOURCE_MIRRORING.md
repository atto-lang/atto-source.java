# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **deny by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers add one exact five-field authorization through a reviewed,
protected-branch change:

`owner/repo repository-id carrier-base-commit carrier-base-tree workflow-blob-sha`

All four identities after the slug are immutable. A slug-only approval, a
floating default branch, or a workflow-only pin is insufficient because the
carrier workflow can execute other files from its base tree.

**Origin protection is mandatory.** Before reading private source files, the
publisher now checks the live GitHub identity of
`ores-truffle-oreslang/oreslang-source.java`, verifies that its official
`main` branch reports `protected: true`, and reads branch-protection rules
from GitHub. It requires `required_pull_request_reviews.require_code_owner_reviews`,
`required_pull_request_reviews.dismiss_stale_reviews`,
at least one required approving review, and `enforce_admins.enabled` to be
true. Force pushes and branch deletions must be explicitly disabled, and any
pull-request review bypass allowances are rejected. Missing or unreadable rules fail
closed, even if GitHub reports `protected: true`. The publisher pins the
policy read to that branch's Git commit and compares its policy bytes
with the local checkout policy.
It repeats these checks immediately before publishing the new CI ref. Merely
adding the allowlist line to an unreviewed checkout does not authorize export,
and a CODEOWNERS file without enforced branch protection is not sufficient.
**At the 2026-10-19 audit, GitHub reported `main protected: false`, so the
publisher deliberately fails closed until administrators enable protection
and verify that CODEOWNER reviews are required.** GitHub returned a 403
for ruleset inspection on the available repository plan, so this tool session
could not independently establish an equivalent enforced ruleset.

Until that prerequisite is met, do not mirror private blobs even if an
allowlist pull request is approved. Source-free test suites are permissible.

Do **not** accept a CI destination allowlist from environment variables or
unreviewed workflow inputs: a caller must not be able to grant its own approval.
Run the checked transfer from trusted, reviewed source, not an untrusted PR
checkout. If an approval policy can be modified by the code under test, treat
the result as untrusted.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. It accepts an approved destination, a bounded local source
directory, and a new isolated CI branch:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci \
  --source-dir ./verified-source-tree \
  --branch ci/oreslang-verified-20261013 \
  --source-commit FULL_40_CHARACTER_ORIGIN_COMMIT_SHA
```

The checked-in policy file must explicitly list the destination, immutable
repository ID, reviewed carrier base commit/tree, and exact carrier workflow
blob. Before reading private source bytes, the publisher verifies the live
destination is still the same private, non-archived, non-disabled, non-fork
repository; its default branch still equals the reviewed base commit; that
commit still resolves to the reviewed base tree; and the workflow blob at that
exact base commit still matches policy. Repointing `main` or modifying any
carrier-local script therefore invalidates authorization rather than silently
changing the execution control plane.

Every GitHub API write is constructed from that **same validated owner/repo**,
rather than a caller-provided URL, Git remote, shell command or separate
destination argument. It creates blobs, then a **fresh standalone source tree
with no `base_tree`**, followed by a root tree that replaces the **entire**
`private-oreslang-source/` directory in the reviewed carrier base tree. This
removes stale source files while preserving only the reviewed carrier control
plane. The carrier commit message binds the exact source commit/tree, carrier
base commit/tree, source repository slug, and immutable source repository ID.

After the final protected-origin recheck, the publisher creates only fresh
refs: the requested `ci/oreslang-*` payload branch and the exact
`ci/authorized-<carrier-commit-sha>` connector authorization ref required by
the reviewed carrier workflow. Existing branches, including `main`, are never
updated. The carrier validates both refs bind the same commit before private
source checkout and is responsible for deleting both ephemeral refs after the
run.

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
guarantee that the source directory itself is free of secrets. Run only
from an authorized protected checkout using narrowly scoped credentials.
There is no permission to use a public repository when CI minutes run out.

Test without network access or sending any source:

```bash
bash scripts/test-private-ci-destination.sh
python3 scripts/test-publish-private-ci-source.py
```

The Bash test uses a temporary policy checkout; Python tests mock all GitHub
API operations. An approval proposal on a feature branch is **not**
authorization: the protected source `main` policy must contain the exact
reviewed tuple, and the publisher's live protected-origin checks must pass,
before any private source bytes may be transferred.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
