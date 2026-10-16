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

The Bash preflight is a separate, testable CLI convenience. The privileged
Python publisher **does not execute** that script from a candidate checkout:
it checks the policy through no-follow directory descriptors and verifies the
pinned destination ID using authenticated GitHub API reads. Calling a Bash
script from a PR-controlled checkout before checking protected-main policy
would provide an unauthorized command-execution path. Operators must launch
the publisher itself from independently reviewed/trusted tooling, not from
the PR's working tree.

The publisher must also compare the local approval file byte-for-byte with
the authenticated Contents API response for the pinned source repository's
`main` branch, including size, encoding, and Git blob identity. That comparison
runs before private-source export and again before every Git object write. A
branch-local approval file alone cannot grant its own export rights.

Exact-tree provenance is a separate condition: operators must supply the
independently reviewed `--source-commit` and **complete checkout-root**
`--source-tree`. The collected candidate snapshot must match its entire
committed `HEAD^{tree}` and equal those supplied immutable SHAs; the commit and
tree SHAs must also be authenticated against the pinned private source GitHub
repository before the first source-bearing write. The published destination
subtree must equal the same reviewed complete-tree SHA. Only checkout-root `.git`
metadata may be omitted; nested Git metadata or source symlinks are rejected.
`GIT_*` environment overrides, Git replacement objects, and caller-provided global/system Git configuration cannot redirect this provenance check.

The candidate's checkout root must contain a real `.git` directory, not a
symlink or a linked-worktree `.git` pointer file. The provenance gate refuses
Git discovery from a parent repository when the checkout's Git metadata is
missing. The publisher also checks the expected commit/tree against GitHub's
authenticated original private-source commit object before any destination
write.


**Required independent gate:** the publisher now denies any transfer unless
GitHub reports source `main` as protected and permits inspection of its branch
protection rules. It requires code-owner review, stale-review dismissal, at
least one approval, strict nonempty required CI, administrator enforcement,
and disabled force pushes/deletions; GitHub must explicitly require the
`preflight-regressions` check **bound to the GitHub Actions App ID 15368**
in `required_status_checks.checks[]`. Legacy `contexts` entries alone are
not trusted because another actor can publish the same context name. A check
with an arbitrary positive App ID, an absent App ID, or a wildcard `-1` is
also denied. Multiple entries for the same security context must all be
bound to GitHub Actions. Configure app-scoped branch protection before
approving a private transfer; unrelated required CI jobs cannot satisfy it. Failure to read these settings is a denial.
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
  --branch ci/oreslang-verified-20261013 \
  --source-commit <REVIEWED_SOURCE_COMMIT_SHA> \
  --source-tree <REVIEWED_COMPLETE_SOURCE_TREE_SHA>
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
The ref-creation acknowledgement must name the exact new branch, commit
object type and expected commit SHA. Before reporting success, the publisher
rechecks protected-main approval, source and destination identities, and
independently reads back the new ref to verify the same commit. This is not
an atomic privacy guarantee; partial uploads or a disputed ref after a
failure still require retention and incident review.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file. The reviewed publisher's code and approval policy remain in its own
trusted checkout; the source candidate may reside in a **different, untrusted
Git checkout**. Its entire committed `HEAD^{tree}` must match the snapshot,
and the commit/tree identity must resolve in the pinned private source GitHub
repository before any source-bearing write. The publisher never runs code
from the candidate checkout merely to authorize mirroring. It walks candidate
source **through directory file descriptors** to prevent parent-directory
symlink swap races. It rejects symbolic links,
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
