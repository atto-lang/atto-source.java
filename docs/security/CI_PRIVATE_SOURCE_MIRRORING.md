# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## October 15, 2026 — immutable destination identity hardening

The approved repository's **numeric GitHub repository ID** is now captured
immediately after the reviewed policy guard passes, *before source files are
read*. The publisher rejects missing, Boolean, negative, zero, or noninteger
IDs. It verifies that the same immutable ID, private visibility, and
full `owner/repo` name still apply before the initial upload and before
**every subsequent GitHub API write**.

The GitHub ref-creation response must identify the exact newly created
commit SHA. The publisher then performs a final visibility/identity check
before reporting success. A name-only approval would otherwise allow a
deleted/recreated private repository to inherit the approved slug during a
long private-source upload.

New offline mocked regressions exercise malformed IDs, replacement after
approval, replacement during blob/tree upload, incorrect created-ref targets,
and visibility or identity changes after ref creation. **These tests are
committed but are not yet an executed CI pass on PR #917:** the source-org
Actions runs continue to fail before running any job steps.

**Residual limits:** these nontransactional API checks cannot prevent a
visibility or identity change *between* a check and its following POST,
nor remove already-uploaded private Git blobs. The checked-in repository
allowlist must still be approved on protected `main` before any
cross-organization private transfer. The production publisher CLI now enforces the independently reviewed full
source-tree SHA; library-level snapshot operations without that pin are for
isolated testing only and must not be reported as exact-tree CI evidence.

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
  --branch ci/oreslang-verified-20261013 \
  --expected-tree-sha REVIEWED_40_HEX_GIT_TREE_SHA
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

The supported CLI additionally **requires an independently reviewed
full Git tree SHA** from the selected source commit. Before uploading any
Git blobs, it computes the complete canonical Git tree hash from the
directory snapshot (including nested directories and executable modes)
and rejects a mismatch. It also rejects a destination isolated subtree
SHA that differs from that same reviewed source tree; the final destination
root commit may differ because it also contains destination workflows.
This refuses the misleading claim that an unverified directory snapshot
is an exact-tree CI execution. The reviewed tree input must be established
independently of the source directory being tested; do not derive the
"expected" hash from that directory during an actual verification.

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
