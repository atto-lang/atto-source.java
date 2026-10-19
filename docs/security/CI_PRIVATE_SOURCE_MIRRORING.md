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


## Pinned source and numeric-ID admission (draft, not yet approved)

In addition to the legacy exact-name approval list, the publisher now calls
`scripts/private_ci_pinned_admission.py` **before reading private source**.
The new protected-main policy file
`.github/private-ci-approved-destinations.json` has a fixed, empty
`repositories` array by default. An entry requires both `name` and an
independently reviewed numeric GitHub repository `id`; the caller may
not approve a destination by supplying an environment variable.

The reviewed checkout must be an unmodified Git commit belonging to the
pinned private Java source repository, GitHub repository ID `1400792274`.
The operator must independently supply two exact 40-hex SHA pins from
trusted automation (not caller-controlled PR inputs):

- `ORES_PRIVATE_CI_REVIEWED_SOURCE_SHA`: the intended exact source commit.
- `ORES_PRIVATE_CI_REVIEWED_POLICY_SHA`: the reviewed current
  `oreslang-source.java` protected `main` commit containing the
  numeric destination-ID approval policy.

The gate compares live source/destination private state and numeric IDs,
requires code-owner review plus administrator enforcement on `main`,
compares local `HEAD` and the Git tree against the remote pinned source,
and refuses dirty/untracked working-tree content. It independently checks
**every collected source file's bytes, mode, name and SHA-1 Git blob identity**
against `git ls-tree` for the exact reviewed source commit **before any
private Git blob is written**. It also checks the numeric destination ID and
reviewed policy main SHA before every Git write.

These admission checks are necessary but **do not by themselves constitute
human security approval**. Security issue #962 also calls for independent
trusted publisher provenance, completed branch-protection acceptance testing
(#904), verification of the final destination subtree and carrier commit,
source disclosure review, and suitable cleanup/retention guarantees.
Missing GitHub protection metadata or a moved reviewed policy SHA must
deny the transfer; they are not reasons to fall back to an unprotected path.

To run the offline adversarial tests without uploading private source:

```bash
python3 -m unittest discover -s tests -p 'test_private_ci_pinned_admission.py' -v
python3 scripts/test-publish-private-ci-source.py
```

**Current authorization: zero destination entries. No live transfer is permitted.**
