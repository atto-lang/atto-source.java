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
  --source-commit <REVIEWED_GIT_COMMIT_SHA> \
  --source-tree <REVIEWED_SUBTREE_SHA>
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

An independently reviewed workflow or security owner must pin **both** the
40-character source commit and the Git tree of the selected source directory
in the original private repository before invoking the publisher. The
publisher rejects missing or malformed pins; checks that the trusted checkout
has exactly that `HEAD^{commit}` and `HEAD:<source-directory>` tree; and
compares every collected in-memory source path, Git executable mode and blob
hash with `git ls-tree -r -z`. Extra, missing, modified and untracked files
fail **before the first GitHub API write**. The newly created isolated private
destination subtree's returned SHA must equal the reviewed source subtree SHA
**before any new commit or ref is created**. This makes the source-subtree
proof stronger than per-blob transport checks. The approval variables/CLI
arguments are *not* themselves independent authorization: their provenance
must be protected by security-owner-reviewed workflows, protected branches,
immutable repository IDs and the still-required destination allowlist. This
publisher does not grant permission to run unreviewed source on any runner.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. It rejects symbolic links,
hardlinks to outside files, FIFOs, `.git` directories, and other unsafe
filesystem objects. The trusted checkout root may have only a real `.git` **directory**, never a regular `.git` pointer file or symlink: such a file can redirect Git object reads outside the trusted checkout. Directory enumeration is bounded to 4,096 entries,
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
