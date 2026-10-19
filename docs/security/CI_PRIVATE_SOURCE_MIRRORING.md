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

## Active release gates (October 2026)

**This is proposed code, not a working or authorized private-source transfer.**
The source repository's GitHub API currently reports `main.protected=false`,
so the proposed publisher now denies private-source transfers. A matching
SHA alone does not prove protected-branch review. Security owners must
configure and verify branch protection (including CODEOWNER review as
appropriate), resolve disclosure incident #832, and independently approve
the exact destination via the protected policy file.

For snapshot mode, the wrapper requires `--source-commit` with an exact
lowercase 40-character Git commit SHA. The publisher verifies that GitHub's
source-commit tree matches the collected snapshot. It checks the local
publisher, wrapper, policy guard and allowlist bytes against their Git blob
identities in the reviewed source-main commit, rechecking those files and the
remote main commit/protected status before each transfer write. This is
defense in depth; it is not proof that an unprotected branch was reviewed.

The separate source-free Git oracle runs in a funded test organization and
must **not** be mistaken for executing this private repository's exact
publisher or compiler tests. Do not transfer private actor trees while the
source allowlist remains empty.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. It accepts an approved destination, a bounded local source
directory, and a new isolated CI branch:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci \
  --source-dir ./verified-source-tree \
  --branch ci/oreslang-verified-20261013 \
  --source-commit EXACT_40_HEX_COMMIT
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

**Committed tree binding (proposed, not yet merged):** The mandatory
`--source-commit` CLI mode gets the immutable source commit's root tree
identity from the fixed private source GitHub repository. The source snapshot
must be inside the independently checked trusted policy checkout; its contents
are collected with bounded, descriptor-relative no-follow reads. The canonical
Git tree SHA reconstructed from those bytes must equal the source commit's
tree **before any source blob is uploaded**. The destination's returned
isolated subtree SHA must also match before root-tree, commit or ref creation.

An internal legacy function path without `--source-commit` still expects an
explicit tracked subdirectory and verifies the local HEAD subtree. It is **not
the supported publishing CLI**, and its weaker behavior must not become a
second transfer entrypoint. Neither branch is authority to export private
source without the reviewed protected-main destination allowlist.

**Additional release dependency:** [security publisher PR #1361](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1361)
has broader source/destination CODEOWNER and workflow review controls. Its
effective review-coverage and branch-bypass checks need to be integrated,
reconciled and tested with this draft before either is used for real private
source transfers. `protected: true` alone is not sufficient proof of enforced
independent CODEOWNER approval.

Destination identity is also pinned to the **same immutable GitHub repository
ID** first obtained at preflight. If a repository is deleted/recreated or
replaced under the same `owner/repo` slug during a transfer, subsequent writes
are refused even if the new repository is private. This proves byte-level tree identity, **not** authorization of a
feature branch, protected-main provenance, CODEOWNER approval or safe private
destination access. The repository's default source-transfer allowlist remains
empty until independently reviewed and merged.

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
