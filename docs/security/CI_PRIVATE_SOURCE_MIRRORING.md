# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add a destination's exact `owner/repo` **and
immutable numeric GitHub repository ID** through a reviewed, protected-branch
change. Each uncommented line must contain precisely `owner/repo 123456789`
(one ASCII space, no extra whitespace); name-only entries, duplicate names,
duplicate IDs, and wildcards are rejected.

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
  --expected-source-tree '<trusted full 40-hex Git tree SHA>' \
  --expected-source-commit '<trusted immutable 40-hex source commit SHA>'
```

A matching policy Git blob on `main` is not authorization by itself. The
publisher now requires a live private and active source repository whose
numeric GitHub ID is `1400792274` and requires the source branch
`main` to report `protected=true` before reading source bytes.
It rechecks this source identity and branch protection before every Git
object write. A missing or unprotected branch denies publication. It also
requires GitHub's protected-branch and required-review endpoints to report
administrator enforcement, strict nonempty required CI checks, at least one
approving reviewer, code-owner reviews enabled, stale approvals dismissed,
and no review bypass allowlist. Missing permissions or unsupported
private-branch rules fail closed. A passing API read is not a substitute for issue #904's independent
synthetic-PR demonstration that direct pushes and unreviewed merges are blocked.
Do not turn the repository public to gain protection features.

The publisher itself must run from an **unmodified Git checkout of the same
protected source-main commit** reported by GitHub. It checks the actual Git
repository root, `HEAD`, and tracked code/policy paths before reading private
snapshot files. An untrusted compiler PR checkout cannot run an altered
publisher or grant itself approval. Prepare the exact compiler PR snapshot as
a separate, bounded directory **inside the trusted main checkout** (for example
using a reviewed `git archive` of the independently authenticated commit).
The source files are still hashed and compared to the remote immutable commit
tree before any destination write. The publisher rejects source-main revision
changes during publication. A source PR alone is never an approval.

Approval must remain disabled until these safeguards and the protections in
issues #904 and #962 have been independently verified.

The protected-main **destination** policy must explicitly approve the exact
private repository name and numeric ID. Separately,
`.github/private-ci-approved-source-snapshots.txt` on protected source
`main` must include a security-reviewed `commitSHA treeSHA destinationNumericID`
tuple for this one transfer. Approving a destination alone does **not**
authorize arbitrary private actor branches or source snapshots. The publisher
checks this tuple before reading any source, validates its Git blob against
the served bytes, and rechecks the approved tuple and unchanged policy SHA
before each write. A missing, unmerged, altered, or revoked tuple denies
publication; authorization cannot come from the working PR branch.

The source-snapshot ledger starts **empty**. To grant one approved run,
maintainers must separately review and merge one exact
`<commitSHA> <rootTreeSHA> <numericDestinationID>` entry on protected source
main. This applies even after the destination allowlist has been approved.
Keep the exact source PR head immutable through verification; any new commit
requires a new reviewed ledger entry and a new destination tree gate.

The publisher checks the live GitHub identity and `visibility=private` /
`private=true` before sending any private bytes and rechecks visibility
immediately before each write. Every GitHub API write is constructed from
that **same validated owner/repo**, rather than a caller-provided URL, Git
remote, shell command or separate destination argument. It creates blobs,
then a **fresh standalone source tree with no `base_tree`**, followed by a
root tree that replaces the **entire** `private-oreslang-source/` directory
in the destination's default tree. This removes stale files from previous
snapshots while preserving unrelated destination workflows and files.
Before reading any source bytes, the publisher also requires the private
carrier's default branch to be `main`, verifies its exact commit ref and
requires GitHub to report `main.protected=true`. It rechecks that protection
and pinned commit before every write. The carrier main tree supplies Actions
workflows that execute with the carrier's own token: an unprotected carrier
must not be trusted to inherit or run those workflows. Protection metadata
alone does not prove independent workflow/CODEOWNER approval; reviewers must
also audit the carrier's workflow definitions and effective rules.
Finally, it creates a commit and a **new** `ci/oreslang-*` ref. Existing
branches, including `main`, are never updated. Before declaring success,
it verifies that the ref-creation response, fresh ref readback, commit tree,
and committed `private-oreslang-source/` subtree all refer to the expected
commit and pinned source SHA. Truncated or missing tree-completeness metadata
is denied rather than accepted as proof. Malformed GitHub metadata is denied
rather than escaping the publisher as an unhandled exception. Destination CI must
independently repeat source-subtree verification before running tests.

The publisher requires a caller-pinned full Git-tree SHA as a mandatory
argument. It reconstructs the Git tree hash from all source file paths, bytes
and executable bits **before** any destination source blob write, then checks
that GitHub's fresh source subtree SHA equals that same pinned value
**before** creating the destination root commit or branch. Missing, invalid,
partial or stale snapshots are rejected. Obtain the expected SHA from an
independently verified immutable source commit, never by calculating it from
untrusted post-change source inputs.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. It rejects symbolic links,
hardlinks to outside files, FIFOs, `.git` directories, and other unsafe
filesystem objects. Directory enumeration is bounded to 4,096 entries,
1,024 directories and a maximum depth of 64. A source snapshot must be
quiescent: the collector verifies opened directory and file inode identity
against the prior inspection, rejects changes in file size, mode, or
nanosecond modification/change timestamps during reads, rejects incomplete
reads, and detects directory metadata changes during traversal. These are
fail-closed race mitigations, not an atomic filesystem snapshot; use a
trusted immutable source checkout and verify the final destination subtree
SHA against its recorded source Git tree. It verifies every uploaded
Git blob SHA-1 against the source bytes. It checks that the reviewed
destination policy has not changed
before every API write, in addition to rechecking the destination's live
private visibility. It does not print source contents in API failures.
The Bash preflight and Python publisher both require the live destination's
positive numeric repository ID to equal the **independently reviewed ID in the
protected policy**, before reading private source or creating Git objects.
The publisher also pins this ID throughout each transfer, checking live name,
numeric identity, private visibility, and active (non-archived, non-disabled)
status before every Git object write. Missing status or an ID mismatch fails
closed, including deletion/recreation under an already reviewed name.

**Limit:** the policy only becomes trustworthy after independent review and
protected-`main` merge. The transfer still has unavoidable API time-of-check/
time-of-use windows between live metadata checks and writes; GitHub ownership
and destination confidentiality require operational controls and retention
review. Approval from an untrusted PR checkout is never valid.

API failure before ref creation can leave unreferenced Git objects in
the private destination. A failed **post-creation readback** can leave a
new but unverified `ci/oreslang-*` ref; never use such a branch as test
evidence. Inspect and clean up only that isolated private ref under the
approved incident/retention process. A follow-up automated retention policy
is still needed.

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
