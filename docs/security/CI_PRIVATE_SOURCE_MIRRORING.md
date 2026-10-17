# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo` destination through a
reviewed, protected-branch change.

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
  --source-commit "$PINNED_SOURCE_COMMIT_SHA" \
  --branch "ci/oreslang-$PINNED_SOURCE_GIT_TREE_SHA"
```

The checked-in policy file must explicitly list the destination as `owner/repo <numeric-id>` (one ASCII space; positive decimal ID without leading zeros). The Bash preflight and Python publisher independently fetch the live GitHub numeric repository ID and require an exact match with the independently reviewed protected-main policy before the first blob upload and before every subsequent GitHub API write. A renamed or recreated repository cannot inherit approval by reusing its slug.
The publisher must also fetch the **origin source repository's protected main**
from GitHub, verify that the local approval and publisher/guard scripts are
byte-for-byte identical to **one immutable protected-main commit SHA**, and
reject if branch protection is not active or `main` moves during verification.
The exporter rechecks protected-source authority immediately before the
isolated destination branch is created and rejects any approval-commit drift,
even if individual policy files were unchanged. This is in addition to the
separate required independent review;
a developer cannot approve the destination by editing their PR checkout.

The caller must pin an immutable origin source **commit**; the publisher
independently obtains that commit's Git tree SHA. It computes the Git tree
of the collected regular-file source snapshot (including nested names and
executable modes) before any blob upload, requires a byte-identical match
with the origin commit, and requires an isolated branch named exactly
`ci/oreslang-<40-lowercase-character-source-tree-sha>`.
It compares the actual newly created isolated destination subtree SHA to
the expected source SHA before creating any destination commit or ref.
The origin protected-main requirements and code-identity checks also mean
a hardening PR cannot authorize itself merely by containing this code.

**This is not yet authorized:** as of the audit, source `main` reports
unprotected and the protected-main destination allowlist is empty.
Do not run the exporter until an independent security review approves a
protected-source policy, and do not interpret an unreviewed local checkout
as authority to transfer private source.

 The
publisher checks the live GitHub identity and `visibility=private` /
`private=true` before sending any private bytes and rechecks visibility
immediately before each write. Every GitHub API write is constructed from
that **same validated owner/repo**, rather than a caller-provided URL, Git
remote, shell command or separate destination argument. It creates blobs,
then a **fresh standalone source tree with no `base_tree`**, followed by a
root tree that replaces the **entire** `private-oreslang-source/` directory
in the destination's default tree. This removes stale files from previous
snapshots while preserving unrelated destination workflows and files.
Finally, it creates a commit and a **new** `ci/oreslang-*` ref. Existing
branches, including `main`, are never updated.

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
API operations. The real policy remains empty and **no destination is
authorized** unless separately reviewed.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
