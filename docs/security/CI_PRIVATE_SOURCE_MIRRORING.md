# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo@numeric-id` destination
record through a reviewed, protected-branch change. For example,
`example-org/private-ci@1412142815` is a format illustration **only** and
must not be treated as a real approval. The exact numeric ID must be the
independently verified, immutable GitHub repository ID—not a branch SHA,
owner ID, workflow run ID, or environment-supplied value.

Name-only policy rows fail closed. The data-only publisher and the
standalone shell diagnostic compare the pinned numeric ID against the live
GitHub repository metadata; a rename/transfer/reuse or inconsistent ID
must be rejected before private source collection. A matching ID is
necessary but **not sufficient**: source/destination protected-main rules,
CODEOWNER approval, exact source-tree attestation, executable trust roots,
incident-resumption authorization, ACLs, retention and workflow confinement
are independent mandatory checks. Current `main` has **no approvals**.

Do **not** accept a CI destination allowlist from environment variables or
unreviewed workflow inputs: a caller must not be able to grant its own approval.
Run the checked transfer from trusted, reviewed source, not an untrusted PR
checkout. If an approval policy can be modified by the code under test, treat
the result as untrusted.

**Active blocker (2026-10-18):** GitHub reports `main` as
`protected: false`, despite checked-in CODEOWNERS metadata. That metadata
does not enforce a review by itself. Administrators must turn on protection
before source mirroring is approved (tracked in private issue #1368). The
publisher now fails closed unless authenticated branch/protection metadata
confirms: protected `main`, at least one mandatory approving review, required
CODEOWNER review, and administrator enforcement. Ruleset-only configurations
cannot silently bypass these checks and need separately reviewed support.

The publisher also authenticates the source `.github/CODEOWNERS` blob on
canonical `main` and verifies the **effective final owner** of each of the
15 export-control paths (policy, publisher, offline tests, workflow and
provenance verification tools). A missing path, later owner override,
unsupported pattern, malformed metadata or mismatched Git blob fails closed
**before source collection**. Ownership is checked again before each Git
write, so an override introduced during an upload stops the next write.
Source CODEOWNERS coverage on currently unprotected `main` is not yet
complete for all 15 paths; this independent trust-root gate must remain
closed until the security PR is independently reviewed and merged.

The trusted-main verification additionally rejects stale code-owner approval
configurations (approvals must be dismissed after new commits), explicit
user/team/app exemptions from required pull-request reviews, enabled or
unknown force-push/deletion permissions, and any admin review bypass.
The publisher checks these controls before accessing source and again before
each destination Git write. A branch with `protected: false` or inaccessible
protection metadata is **not** an approved source of an export.

**Runner-credential limitation:** GitHub's
`GET /repos/{owner}/{repo}/branches/{branch}/protection` endpoint requires
repository **Administration:read** permission. A default Actions
`GITHUB_TOKEN` with `contents:read` cannot satisfy that requirement.
Consequently, this publisher fails closed under an ordinary unprivileged
runner even after branch protection is enabled. Do not grant a broadly
privileged reusable secret to a temporary runner or bypass the inspection.
A separately reviewed, narrowly scoped attestation/runner authorization
design is required before enabling a real cross-organization transfer.
Source-free simulations cannot substitute for that end-to-end proof.

The publisher also retrieves the canonical source repository's
`.github/private-ci-approved-repositories.txt` from the authenticated
GitHub API at `ores-truffle-oreslang/oreslang-source.java` on `main`. The
returned blob identity, encoding and byte length must be valid, and its
contents must SHA-256 match the checked-out local allowlist **before
collecting source**. Protected-main agreement is checked again before
creating the destination branch ref. Editing an unreviewed checkout's local
allowlist cannot independently authorize a private-source transfer.

This runtime check does **not** prove the GitHub `main` branch has adequate
branch protection, nor does it validate CODEOWNER review, ACLs or cache and
artifact retention. Maintainers/security owners must establish those
controls independently. A forged local script can bypass its own checks;
run only reviewed publisher code from a trusted checkout.

## Reviewed merged-main snapshots

The publisher's `--source-merged-pr` mode is separate from the
`--source-pr` mode used for still-open pull requests. It permits the exact
Git tree of a **current, protected `main` tip** only when that tip equals
the named, already merged pull request's `merge_commit_sha`. The PR must
belong to the canonical source repository and target `main`, and the
CODEOWNER's approval must apply to the PR's exact pre-merge HEAD commit.
The approval's submitted time must **not be later** than the merge timestamp;
timestamps must be valid timezone-aware instants. Closed-but-unmerged PRs,
old main commits, shifted main tips and later or missing reviews are rejected.
The complete attestation and branch protection are checked again before
each destination write, not only during initial preflight.

For the separately authorized post-merge mode:

```bash
scripts/run-in-private-ci-destination.sh APPROVED_PRIVATE_OWNER/REPO \
  --source-dir ./verified-source-tree \
  --branch ci/oreslang-verified-main \
  --expected-source-tree VERIFIED_MAIN_TREE_SHA \
  --source-merged-pr REVIEWED_MERGED_PR_NUMBER \
  --expected-source-commit VERIFIED_LIVE_MAIN_COMMIT_SHA
```

**Important, October 18, 2026:** merged Java PR #1286 currently has
no recorded CODEOWNER review, and source `main` is unprotected. Therefore
that PR **does not qualify** for an export, even though its merge commit
and tree are known. Do not bypass this denial or manufacture a retrospective
review; establish independently reviewed authorization via a protected
source policy and suitable reviewed source snapshot instead. No destination
is approved in the current `main` allowlist.

## Preflight command-execution boundary

The private publisher does **not** execute
`scripts/check-private-ci-destination.sh` from a source checkout as a
preflight. Before protected-main authorization, checkout scripts may be
unreviewed. Running their Bash code would execute arbitrary commands with
the operator's credentials even if the transfer were later rejected.
Instead, the publisher's `require_preflight` performs data-only validation
of the local allowlist through descriptor-relative, no-follow policy reads
and authenticated GitHub checks that the destination repository is private.
Protected-source branch-policy and authorizing blob checks remain mandatory
separate gates. The shell guard remains available for standalone diagnostics
and its own test suite; it is not an export authorization authority.

## Trusted GitHub CLI executable

Private-source publication uses an authenticated GitHub CLI and must not
resolve the executable through the caller's `PATH`. The publisher requires
an **OS-packaged, root-owned, executable regular file at `/usr/bin/gh`**.
Its `/`, `/usr`, and `/usr/bin` parent directories must also be
root-owned and not group/other writable. Missing binaries, symlinks,
non-root owners, nonexecutable files and writable path components fail
closed before GitHub credentials are passed to a subprocess.

The GitHub subprocess now receives a minimal, explicit environment: exactly
one nonempty `GH_TOKEN` **or** `GITHUB_TOKEN` must be supplied by the trusted
invoker. Missing or simultaneous credentials are denied before any API
subprocess. No implicit `gh auth login` user configuration is accepted.
`GH_HOST` is forced to `github.com`, prompts are disabled, executable lookup
is fixed at `/usr/bin:/bin`, and HOME/XDG_CONFIG_HOME do not point at an
inherited caller configuration. `HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`,
`SSL_CERT_FILE`, `SSL_CERT_DIR`, `CURL_CA_BUNDLE`, dynamic loader hooks,
`GH_CONFIG_DIR`, and every other caller-supplied environment variable are
excluded, preventing the caller's environment from redirecting the approved
GitHub connection or replacing its TLS trust roots. Only inject an approved
short-lived, narrowly scoped credential via the trusted publisher host;
do not print, log, copy, or persist it in Actions artifacts. This change
intentionally rejects proxy-dependent hosts until a separately reviewed
transport policy exists.

Administrator-managed hosts where `gh` is installed elsewhere (including
typical Homebrew locations) must provision the trusted system package path,
or develop and independently review an equivalent executable-pinning policy;
do not bypass the check with a user-writable symlink. This requirement
applies to the publisher, not its local offline regression runner.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. It accepts an approved destination, a bounded local source
directory, and a new isolated CI branch:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci \
  --source-dir ./verified-source-tree \
  --branch ci/oreslang-verified-20261013 \
  --expected-source-tree EXACT_SOURCE_PR_GIT_TREE_SHA \
  --source-pr 1286 \
  --expected-source-commit EXACT_SOURCE_PR_HEAD_COMMIT_SHA
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
The destination workflow/tooling baseline is pinned to the exact default
branch name and commit SHA observed before uploading. Before **each** Git
blob/tree/commit/ref write, the publisher re-fetches destination identity,
default-branch name and live HEAD SHA; a move, branch switch, missing ref or
malformed SHA aborts further writes rather than publishing a CI branch based
on stale workflow definitions. This is fail-closed defense-in-depth and cannot
eliminate the narrow check-to-write race or dangling blobs already sent.

The publisher additionally checks **destination-side branch protection**
before the first upload and before every subsequent Git write. The exact
pinned destination default HEAD must be protected by mandatory CODEOWNER
review, stale-review dismissal, administrator enforcement, zero user/team/app
review-bypass grants, and disabled force-pushes/deletions. Inaccessible or
malformed protection metadata fails closed. This is separate from source-side
authorization: a stable destination commit does not by itself prove that
changes to the workflows it contains were independently reviewed.

Because a branch-protection toggle cannot prove that any workflow is actually
owned, the publisher also reads `.github/CODEOWNERS` from the **pinned
destination commit SHA**, verifies its declared length and Git blob digest,
and requires the **entire effective non-comment policy** to contain
exactly these three rules, in order:

```text
* @ORESoftware
/.github/ @ORESoftware
/.github/CODEOWNERS @ORESoftware
```

Checking only the terminal workflow rules is insufficient: an intervening
`/scripts/ @untrusted` rule can override the global owner for provenance
scripts without changing the final two lines. Extra active rules, owner
substitutions, missing entries, or unreadable/forged CODEOWNERS bytes all
deny export **before the first private blob is uploaded**. The destination
must install this exact policy through independent security review; an
unreviewed draft branch is not authority.

The destination's default branch is pinned to one exact
`refs/heads/BRANCH` reference with explicit Git object type `commit`.
Missing or ambiguous ref names, annotated-tag targets, malformed tree
objects, and default-branch movement during upload are denied. These
checks happen before source writes and before each subsequent Git write.

Before writing even one private Git blob, the publisher requires a 40-hex source tree SHA independently verified from the intended source PR commit.
It also requires the **specific source PR number** and the **expected source
PR HEAD commit SHA**. Through the authenticated GitHub API it checks that the
PR remains open, its head and base belong to the canonical private source
repository, its target is `main`, the PR's current head exactly matches the
pinned commit, and the pinned commit's Git tree exactly matches the supplied
tree. It additionally requires a live, independently submitted **exact-HEAD
CODEOWNER approval** from the trusted security reviewer: an open same-repo PR
with a matching SHA is not itself authorization. Draft PRs, self-review,
prior-commit approvals, dismissed/changes-requested current approvals, invalid
review chronology and incomplete review pagination all fail closed.
Review timestamps are parsed as timezone-aware instants, rather than compared
as raw strings. This approval is rechecked prior to each destination write.
The mock suite exercises missing, stale, revoked and reordered approvals;
these tests are not a substitute for running the complete publisher suite.
It rechecks both PR HEAD and commit/tree **before every Git
blob/tree/commit/ref write**, including the final destination-ref creation:
a force-push, new commit, closure or retargeting stops the *next* write,
rather than permitting additional uploaded blobs while waiting for ref
publication. Already-uploaded objects may remain dangling in the private
destination; pre-write rechecks cannot eliminate that retention or the
check-to-write race.
Do not derive the source PR number and expected source commit from an
unreviewed workflow input; the authorized CI operator must independently
choose and attest them. It reconstructs the canonical Git tree from the complete collected paths, modes and bytes and refuses a mismatched snapshot. It then requires GitHub's fresh isolated destination subtree SHA to match that same source tree before creating any commit/ref. A digest supplied by unreviewed PR policy is not independent authorization.

Before a destination commit is created, the publisher now independently
GETs both the pinned destination base root tree and the newly created
destination root tree. Both responses must name the exact requested tree
object, be **complete** (`truncated: false`), and contain unique, valid
root entries. The stored new root must be byte-for-byte equivalent at
the Git-entry identity level (path, mode, object type and SHA) to the
pinned old root, with exactly one intentional replacement:
`private-oreslang-source/` must point to the separately attested source
subtree. Missing/replaced `.github` entries, unexpected workflow changes,
extra files, deleted unrelated entries, duplicate names and ambiguous
or incomplete tree responses fail before commit/ref creation. This guards
against blindly trusting a tree-creation response and protects the pinned
destination workflow baseline; it does not authorize export or eliminate
the usual remote API check-to-write window.

The corresponding three additional offline publisher regression methods
were committed on 2026-10-18 and **are not yet verified by a hosted Python
suite**. The funded source-free Git-tree oracle remains separate evidence.

Finally, it creates a commit and a **new** `ci/oreslang-*` ref. Existing
branches, including `main`, are never updated.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. It rejects symbolic links,
hardlinks to outside files, FIFOs, `.git` directories (including
case-insensitive spellings such as `.GIT`), and other unsafe
filesystem objects. The canonical Git-tree digest independently rejects
unsafe path components, including `.git`, control/NUL/DEL bytes,
backslashes, empty components, parent traversal and duplicates. The
collector and digest must have matching fail-closed name admission
semantics, because digest callers must not be able to bypass collector
validation. Directory enumeration is bounded to 4,096 entries,
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

## Strict reviewed workflow boundary

The offline review-coverage gate protects the complete `preflight-regressions`
job, not just matching step names anywhere in a workflow. It permits a single
security job with exactly four steps: pinned checkout with
`persist-credentials: false`, review-coverage validation, offline runner
self-test, and the complete publisher regression execution step. Extra
checkout/action steps, additional jobs, edited checkout identities, disabled
or masked tests, and renamed or modified executable security commands deny
the review-coverage check.

This protects against false-positive source-only test attestations. It is
**not** a substitute for protected branch rules and independent CODEOWNER
review; unreviewed workflow modifications are still untrusted until GitHub
actually enforces the reviewed source policy. The security regression file
`scripts/test-private-ci-review-coverage.py` contains 28 cases for this
boundary. The 28/28 local result is specific to the validator and does not
certify the complete publisher suite or compiler.

## Offline publisher regression execution

These checks **do not transfer source**. From a trusted authorized checkout of
this security PR, run:

```bash
bash scripts/test-run-private-ci-regressions-local.sh
env -i PATH=/usr/bin:/bin HOME=/ PRIVATE_CI_TEST_REPEATS=3 \\
  /bin/bash scripts/run-private-ci-regressions-local.sh
```

The publisher runner requires an **empty parent environment**, established
**before Bash starts**, not merely `unset BASH_ENV` inside the script.
Inherited `BASH_ENV` can execute during Bash startup and `LD_PRELOAD`
can affect the loader before the first shell statement. The runner now
rejects detectable hook-bearing direct launches, but that detection cannot
undo any already executed startup code. Trustworthy receipts require the
`env -i ... /bin/bash` form above (also pinned by the Actions workflow).

The first command tests the runner against synthetic fixtures. The second
compiles the actual publisher and its Python regressions, syntax-checks the
actual Bash guards, executes the destination denial tests, and repeats the
complete Python publisher suite (which also discovers merged-main tests)
three times. It also executes the review-coverage validator, rather than
only checking that the validator exists. The runner refuses a caller-selected
Python executable (including `PYTHON=/bin/true`), invokes the genuine
`python3` interpreter in isolated mode (`-I -B`), removes inherited GitHub
tokens and Python startup hooks, and uses an empty temporary GitHub CLI
configuration. Its SHA-256 manifest pins the nine script/test inputs plus
the source CODEOWNERS file, protected destination allowlist, and private-CI
workflow definition before and after execution. Twenty-four source-free
adversarial runner self-tests check failure behavior, including interpreter
substitution, Python startup injection, symlinks, mid-run policy edits,
missing merged-main discovery, a truncated test suite, and a **full-size**
test suite where a mandatory security regression has been replaced with
an unrelated passing test.
Every publisher-suite repetition independently discovers and executes the
same Python TestSuite; the publisher requires at least 107 canonical
publisher tests and 10 merged-main cases (117 total), unique test IDs,
equal discovered/executed counts, and mandatory adversarial test IDs
for source-policy revocation, destination CODEOWNERS revocation,
root-tree/commit/ref read-back, explicit-token isolation and TLS/proxy
injection rejection, and merged-main provenance. The full suite additionally
rejects **any** skipped test, expected failure or unexpected success, even
when unittest would normally report success or count the test as run.
Runner self-tests exercise both skip and expected-failure masking of a
mandatory security case.
These checks are a regression-inventory guard, **not an attestation**
that arbitrary renamed test bodies retain their intended assertions.
A single passing test is not sufficient.
The review-coverage verifier additionally pins the executable full-suite
workflow step, denying conditional skips, result masking and inert early exits
as well as step- and job-level skip/continue-on-error controls.
The runner emits `PRIVATE_CI_COMPLETE_REGRESSIONS_PASSED=1` **only** when
every step succeeds and the input manifest is unchanged. These controls
isolate credentials and Python imports, but are **not** a network sandbox:
run offline or in a network-restricted environment.

Local validation on October 18 used the exact committed Git blobs
for the runner, review-coverage validator, CODEOWNERS and workflow.
The review validator passed 21/21 tests; a separate synthetic fixture
exercised the earlier runner with its then-current 107-test minimum and
six adversarial checks. The new 117-case floor, revised synthetic self-test,
and full publisher suite are not yet verified by a hosted runner. These
historical results are NOT execution of the current complete Python suite. The source org's Actions remains unallocated (zero job steps).
Do not publish sensitive blobs to a different org based on synthetic tests.

Until the actual command above produces its final success receipt and
independent source branch/destination approvals are complete, CI publication
remains **not authorized**. Neither source-free funded-runner results nor
the local runner's synthetic smoke tests constitute execution of the full
publisher suite.



## Operator checkpoint: enforce and independently verify branch protection

The private-CI publisher **must not export** Oreslang private source while
`ores-truffle-oreslang/oreslang-source.java` or a proposed destination such
as `sagitta-stack/sagitta-infra` reports `branches/main.protected=false`.
A checked-in CODEOWNERS file alone is not a branch protection rule. As of
2026-10-18, both repositories reported unprotected `main`.

An independent repository administrator should configure **classic branch
protection** for each repository's `main` (rather than assuming rulesets are
interpreted by the current publisher attestation). Apply only after reviewing
whether required status checks, existing policies, merge modes and CODEOWNERS
coverage need to be retained. The required controls are:

- Mandatory pull-request reviews, at least one approval, and mandatory
  CODEOWNER review; dismiss stale approvals when new commits arrive.
- Require last-push approval and resolution of conversations as a further
  defense in depth. This is stricter than the publisher's minimum admission.
- Enforce restrictions for repository administrators; no named user, team
  or app may bypass review.
- Disable force pushes and branch deletion. Keep the destination private
  and verify its access, runner, workflow, artifact and cache retention controls.

A minimal GitHub REST branch-protection **proposal**, not an authorization
to apply automatically, is:

```json
{
  "required_status_checks": null,
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false
}
```

This proposal explicitly sets `required_status_checks` to `null` and would
remove any existing required checks, so **do not blindly overwrite an
existing protection** with it. Capture the current default
branch SHA and policy first. Only make reviewed changes on exact known private
repositories using an admin-authorized GitHub identity. Re-read the live
`branches/main` and `branches/main/protection` endpoints *after* applying
and fail closed if any effective control differs or the default tip moved.

This administrative step is distinct from the destination allowlist approval.
Do not populate `.github/private-ci-approved-repositories.txt`, mark security
PRs ready, merge an approval, or create a private source blob as a substitute
for independently reviewed protection and private destination approval.

**Execution evidence is separate:** GitHub Actions failures with no
`jobs[].steps` prove neither passing nor failing unit assertions. The complete
private publisher suite must run against the exact reviewed source head in an
authorized execution environment; independently authored source-free CI models
cannot substitute for that suite. Incident #832 (earlier public CI exposure)
requires separate retention and Git-object containment before signing off
cross-organization private source movement.
