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
**all review conversations resolved** (`required_conversation_resolution.enabled`
must be `true`), and no review bypass allowlist. Missing permissions or unsupported
private-branch rules fail closed. A passing API read is not a substitute for issue #904's independent
synthetic-PR demonstration that direct pushes and unreviewed merges are blocked.
Do not turn the repository public to gain protection features.

The publisher's POSIX transfer wrapper starts Python in isolated mode and
drops inherited Bash startup, Python import-path and dynamic-loader variables.
The publisher also removes shell/Git startup and configuration overrides
from child-process environments, and resolves `bash`, `git`, and `gh` from
system executable directories. These checks mitigate ambient environment
injection; the invoking runner/host and its system executable directories
must themselves be trusted. Do not execute the transfer from an untrusted
PR-controlled environment.

The publisher itself must run from an **unmodified Git checkout of the same
protected source-main commit** reported by GitHub. It checks the actual Git
repository root and immutable `HEAD`, then performs descriptor-relative,
no-symlink byte hashing of every trusted script and policy file against
its exact `HEAD` Git blob and mode, before reading private snapshot files.
Git `status --porcelain` is insufficient: `skip-worktree` and
`assume-unchanged` index flags can make altered executable bytes appear
clean. The verifier bypasses Git replace-object mappings and does not trust
the index as proof of worktree contents. An untrusted compiler PR checkout cannot run an altered
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
remote, shell command or separate destination argument. It creates a **fresh
standalone source tree without the carrier `base_tree`**, optionally building
that source tree through bounded incremental Git Trees API batches. Each
incremental batch derives only from the prior *isolated source tree*. GitHub
creates blobs for bounded UTF-8 entries during those tree writes; binary
and oversized files use individually verified blob uploads. The completed
source tree must match the pinned Git tree SHA. The final root tree replaces
the **entire** `private-oreslang-source/` directory
in the destination's default tree. This removes stale files from previous
snapshots while preserving unrelated destination workflows and files.
Before reading any source bytes, the publisher also requires the private
carrier's default branch to be `main` and verifies its exact commit
ref. A simple `main.protected=true` flag is insufficient: destination main
must also enforce administrator restrictions, strict nonempty CI status
checks, resolved review conversations, at least one approving reviewer,
code-owner review, stale-review dismissal, and no reviewer bypass allowances.
Missing/denied GitHub protection responses fail closed **before reading source**.
The carrier must also have a valid protected-main `.github/CODEOWNERS` whose
last two non-comment rules are exactly `/.github/CODEOWNERS @ORESoftware`
and `/.github/workflows/ @ORESoftware`, in that order. The exporter
verifies the CODEOWNERS file's Git blob digest, checks GitHub's official
CODEOWNERS diagnostics, and confirms that this security owner has effective
write/admin permission on the *carrier*, not only the source. Without this
coverage, GitHub's `require_code_owner_reviews` flag does not prove that
changes to inherited CI workflows received security-owner review.
These requirements are rechecked before each destination Git-object write
along with the pinned main commit. The carrier main tree supplies Actions
workflows that execute with the carrier's own token. Even verified branch
protection metadata is not proof of a safe workflow: independent reviewers
must still audit the carrier's effective rulesets and executable workflow
definitions for exfiltration and unsafe privilege escalation.
Finally, it creates a commit and a **new** `ci/oreslang-*` ref. Existing
branches, including `main`, are never updated. Before declaring success,
it verifies that the ref-creation response, fresh ref readback (including
exact ref name and object type), base and newly created commit identities,
root tree identity, and committed `private-oreslang-source/` subtree all refer to the expected
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
The standalone Bash diagnostic and the Python publisher both require the live
destination's positive numeric repository ID to equal the **independently
reviewed ID in the protected policy**, before reading private source or
creating Git objects. The production Python publisher **does not execute**
a mutable checkout-local Bash guard: it instead performs its own no-follow,
bounded policy read and authenticated live destination lookup, eliminating
the pathname-swap window between guard attestation and interpretation.
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

## CODEOWNERS coverage must be independently verified

The publisher now reads `.github/CODEOWNERS` from authenticated source
`main`, checks the returned Git blob SHA-1 and size against the actual
base64-decoded file bytes, and requires a terminal block of exact
`@ORESoftware` ownership entries for the CODEOWNERS file itself,
destination and source-snapshot approval ledgers, export entry point,
destination guard, audit tests and private-mirror CI workflow.

The terminal ordering is intentional: in CODEOWNERS a later matching rule
wins. An earlier security rule followed by an unreviewed wildcard or
specific-file rule must not silently remove the review requirement.
Missing, renamed, tampered, overridden or malformed ownership fails closed
**before** the private compiler source snapshot is read.

This is additional verification, not authorization by itself: protected
source `main`, effective independent review enforcement, immutable source
commit/tree approval, and numeric destination repository ID must also pass.
Current `main` does not yet contain the proposed source-snapshot policy
ownership line, and no private export is authorized. CODEOWNERS checks
cannot prove that a repository owner configured GitHub permissions correctly,
or that later users with administrative access cannot alter settings.

The publisher also consults GitHub's protected-main
`GET /repos/{owner}/{repo}/codeowners/errors?ref=main` endpoint and
`GET /repos/{owner}/{repo}/collaborators/ORESoftware/permission`.
The first must return an empty `errors` array; the second must identify
the expected GitHub **User** with effective `write` or `admin` access.
These checks must reject missing API permissions or unavailable endpoints,
rather than accepting locally well-formed but GitHub-inactive owners.

The exact CODEOWNERS validation function was exercised locally with
**28/28 focused cases** and a matching source-text FNV-1a fingerprint
(`37bd9d4b`, 2713 characters including the terminating newline).
These are source-free mocked GitHub API checks, not the full checked-in
Python/Bash publisher suite, Java/GraalVM tests, or a real source transfer.

Security reviewers must separately verify GitHub's CODEOWNERS diagnostics
and that a qualified **independent** reviewer can approve a change, rather
than relying solely on a valid CODEOWNERS text entry.

## GitHub API rate-limit preflight

The publisher re-authenticates the source and destination security boundary
immediately **before every Git object write**, including Git Trees API writes
that create multiple blobs together. Batching reduces *the number of writes*;
it does not skip any per-write authorization. GitHub Actions minutes and GitHub
REST API budgets are separate constraints.

For a source snapshot with `N` files, UTF-8 content of up to 128 KiB per file
may be sent inside isolated tree POSTs when `N >= 16`; otherwise files use
individually verified blob uploads. Batches are bounded to at most 64 entries
and 256 KiB of accounted input bytes. Non-UTF-8, NUL-containing, and larger
files use separate Git blob POSTs with SHA-1 verification. The first isolated
source tree is constructed without a base tree; subsequent partial trees
use only the *prior isolated tree* as a base. The completed source tree must
match the independently pinned full Git SHA **before** building the carrier
root tree or creating a branch.

After computing and verifying the complete local snapshot, the publisher
checks `core.remaining` before its first destination write. Its conservative
reserve is `24 * max(1, blob_posts + isolated_tree_batches - 1) + 160`.
This accounts for repeated authorization checks on *each* write plus fixed
root-tree/commit/ref creation and readback. Invalid or insufficient quota
metadata fails closed. This is an estimate, not a reservation, and does not
guarantee avoidance of concurrent GitHub API consumption or secondary limits.

Example: 554 small UTF-8 files can fit in nine tree batches with no separate
blob POSTs, yielding a modeled admission reserve of **352** core requests
instead of the previous **8,992**. This is an illustrative source-free
snapshot, **not a measured benchmark of the exact Java compiler tree**:
real file sizes, encoding, paths and batch counts determine its actual cost.

Git Trees API incremental behavior was checked using synthetic files in an
approved-access private carrier without creating a branch or transferring
compiler source. An isolated local test harness checked 800 deterministic
randomized batching cases and confirmed a synthetic final tree SHA matches
GitHub's readback. These are *targeted tests*, not a passing run of the
full checked-in Python/Bash exporter suites. Those suites still require a
working trusted runner and independent security review before export.

On October 15, 2026, a separate source-free GitHub Actions probe in private
`benefactor-cc/backend.rs` succeeded:
`https://github.com/benefactor-cc/backend.rs/actions/runs/38763080924`.
The job executed three steps and verified 141 synthetic files (including
one binary and an executable), three bounded source-tree batches, and one
binary blob POST: **four** Git object writes total. The SHA was identical
to independently computed `git write-tree`. This does **not** authorize
private Oreslang source transfers or replace the exporter regression suite.
The Benefactor `main` branch currently remains unprotected and lacks the
required effective CODEOWNERS entry, so it is **not** an approved carrier.


The follow-up source-free [Benefactor run #38768616381](https://github.com/benefactor-cc/backend.rs/actions/runs/38768616381)
also passed with **three executed job steps** on its exact synthetic workflow
revision `08ab4a1e77966971042cda94861f6604fc6c91fb`. It built a
**554-file** tree (553 short UTF-8 files, one binary; one executable mode),
using **nine** incremental Git-tree requests plus **one** binary-blob POST.
The resulting SHA `87ee19c8299b43ccb9032b856f1f89f3c713d5ef`
matched local `git write-tree`. The live test performed **10** Git-object
POSTs to create the isolated source subtree; it deliberately did **not**
create the final destination root tree, commit, or CI ref. The production
publisher would require three additional writes, for an expected **13**
writes in this specific synthetic example. Its modeled admission reserve
is `24 * (1 + 9 - 1) + 160 = 376` core requests; this is not an
observed measurement of rate-limit consumption.

A separate source-free local test script exercised 2,500 deterministic
randomized batching scenarios and nine file-count boundary cases. These
measure the batching algorithm, not all security checks in the private
publisher. The protected-source approvals and independent security review
requirements still apply and the full Python/Bash exporter suites remain
unexecuted.
