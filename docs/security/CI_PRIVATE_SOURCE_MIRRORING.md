# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is
**deny-by-default**. Active rows use
`owner/repo@numeric-github-repository-id`; repository names alone are not an
authorization boundary. The live GitHub repository ID must still match the
reviewed immutable ID immediately before export writes.

The preferred authorization mode is a reviewed policy on protected `main`.
When branch protection is technically unavailable, the only staged fallback is
`review-attested-v1`: each export needs two distinct, unedited GitHub issue
comments from the pinned maintainer and security owner. The fallback may be
considered only when GitHub explicitly reports that both classic branch
protection and repository rulesets are unavailable because of the current
private-repository plan; a generic 403/404, disabled setting, or integration
permission failure is not sufficient evidence and fails closed.

Both comments must be the exact same one-line payload binding the live source
PR/head/tree, destination name/ID, exact new `ci/oreslang-*` target branch,
reviewed destination carrier base commit/tree, and the authoritative-main blobs
for the policy, preflight, destination guard, wrapper, and publisher. Any bound
change voids the comments. The fallback is not active merely because its code
exists on a feature branch; it must itself be independently reviewed and merged
first.

Do **not** accept a destination allowlist or approval payload from arbitrary
environment variables or unreviewed workflow inputs. The source checkout may
be the exact PR tree being tested, but every security-critical export program
must hash identically to its reviewed `main` blob before the preflight passes.
A PR therefore cannot weaken its own exporter.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. It accepts one reviewed destination, a bounded local source directory, a new
isolated CI branch, and the exact source PR/commit/tree authorization:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci \
  --source-dir . \
  --branch ci/oreslang-verified-20261019 \
  --source-pr 1425 \
  --source-commit <40-hex-live-pr-head> \
  --source-tree <40-hex-exact-tree> \
  --maintainer-approval-comment <comment-id> \
  --security-approval-comment <comment-id>
```

The two comment flags are omitted only when protected-main authorization is
effective. Before reading potentially sensitive source bytes, the publisher
runs `preflight-private-ci-mirror.sh`. That preflight verifies the exact clean
source checkout, live same-repository PR head, fresh authenticated `main`,
authoritative security-program blobs, applicable approval mode, policy row, and
destination identity. It also requires the destination default branch, carrier
base commit, and carrier base tree to equal the reviewed values embedded in the
authoritative exporter. A carrier workflow/configuration change therefore
invalidates authorization before private source bytes are read. The checked-in
policy must explicitly list `owner/repo@numeric-id`.

The publisher then checks the live GitHub repository ID, full name and
`visibility=private` / `private=true` before sending private bytes, and
rechecks the immutable ID and private visibility immediately before each
write. Every GitHub API write is constructed from
that **same validated owner/repo**, rather than a caller-provided URL, Git
remote, shell command or separate destination argument. It creates blobs,
then a **fresh standalone source tree with no `base_tree`**, followed by a
root tree that replaces the **entire** `private-oreslang-source/` directory
in the destination's default tree. This removes stale files from previous
snapshots while preserving unrelated destination workflows and files.
Finally, it creates a commit and the **new, approval-bound**
`ci/oreslang-*` ref. Existing branches, including `main`, are never updated.
The publisher also rejects a destination default-branch, base-commit, or
base-tree change after review instead of silently inheriting a changed carrier
workflow.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. For an exact repository-tree
export, pass the trusted checkout root as `--source-dir .`; the root
`.git` metadata entry is excluded because it is not part of `HEAD^{tree}`.
Nested `.git` paths remain rejected. The publisher also rejects symbolic
links, hardlinks to outside files, FIFOs, and other unsafe filesystem
objects. Directory enumeration is bounded to 4,096 entries,
1,024 directories and a maximum depth of 64. It verifies every uploaded
Git blob SHA-1 against the source bytes. It checks that the reviewed destination policy fingerprint and immutable
repository ID have not changed before every API write, in addition to
rechecking the destination's live private visibility. It does not print source contents in API failures.
API failure before ref creation can leave unreferenced Git objects in
the private destination; a follow-up retention policy is still needed.

It does **not** intercept arbitrary connector/API tool writes, cannot
completely prevent visibility changes between API checks, and cannot
guarantee that the source directory itself is free of secrets. Run only through the reviewed authorization path using narrowly scoped
credentials. There is no permission to use a public/internal repository, or to
treat available Actions minutes as source-export approval.

Test without network access or sending any source:

```bash
bash scripts/test-private-ci-destination.sh
bash scripts/test-preflight-private-ci-mirror.sh
python3 scripts/test-publish-private-ci-source.py
```

The Bash tests use synthetic Git/policy/API fixtures; Python tests mock all
GitHub API writes. They never transfer Oreslang source. A policy row on a draft
branch is **not authorization**: the applicable protected-main or independently
reviewed dual-attestation gate must also pass.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
