# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo` destination through a
reviewed, protected-branch change.

For a future approved destination, the checked-in allowlist is **necessary
but not sufficient**. The Bash preflight and Python publisher both require
the same independently reviewed immutable numeric GitHub destination ID;
the publisher additionally verifies the private source repository ID. Both
must reject renamed/recreated destinations, archived or disabled repositories,
and incomplete API identity metadata. The pins are code-owner-reviewed and
must stay synchronized (the publisher's offline regression suite checks this).
Do not insert an ID into a bare-slug allowlist line; it violates the policy
parser. An ID hardcoded in software is not itself permission to export.

The publisher must also compare the local approval file byte-for-byte with
the authenticated Contents API response for the pinned source repository's
`main` branch, including size, encoding, and Git blob identity. That comparison
runs before private-source export and again before every Git object write. A
branch-local approval file alone cannot grant its own export rights.

Exact-tree provenance is a separate condition: the published source subtree
must match the entire checkout's committed `HEAD^{tree}`, and its commit SHA
and tree SHA must be authenticated against the pinned private source GitHub
repository before the first source-bearing write. Only checkout-root `.git`
metadata may be omitted; nested Git metadata or source symlinks are rejected.
`GIT_*` environment overrides cannot redirect this provenance check.

**Required independent gate:** the publisher now denies any transfer unless
GitHub reports source `main` as protected and permits inspection of its branch
protection rules. It requires code-owner review, stale-review dismissal, at
least one approval, strict nonempty required CI, administrator enforcement,
and disabled force pushes/deletions; failure to read these settings is a denial.
This checks current enforcement settings, not the authenticity of a particular
reviewer's decision or an exemption outside the reported rules. A pending
allowlist PR never authorizes a transfer. The security owner must independently
review the actual protection and approvals before any source export.

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
  --branch ci/oreslang-verified-20261013
```

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
