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
  --branch 'ci/oreslang-<source-commit-SHA>-<reviewed-carrier-main-SHA>' \
  --expected-source-tree '<trusted full 40-hex Git tree SHA>' \
  --expected-source-commit '<the same exact 40-hex source commit SHA>'
```

**Bootstrap trust is external to the source checkout.** The wrapper and Python
publisher cannot prove their own integrity before their first instructions
execute. Therefore the command above is a publisher interface, not a root of
trust, and it must not be launched directly from a merely caller-supplied
checkout. The independently reviewed private carrier provides
`scripts/launch-oreslang-private-ci-publisher.py`, which first authenticates
the private source repository and exact protected-`main` commit, fetches
`scripts/publish-private-ci-source.py` from GitHub at that immutable revision,
requires an independently supplied expected **publisher Git blob SHA**, verifies
the returned bytes against that SHA, and only then executes those exact bytes
under isolated Python. The bootstrap injects the already verified source-root
path directly into the trusted publisher execution context; repository-root
selection does not depend on resolving the mutable checkout-local publisher
pathname. Local publisher bytes are never executed before this attestation.

The expected publisher blob SHA is approval data and must come from an
independent security review / protected carrier trust boundary, never from the
source checkout being authenticated. Carrier CODEOWNERS must terminally cover
the bootstrap launcher, and source-side admission verifies that coverage.
Until carrier `main` itself is independently protected and reviewed, the
bootstrap remains test scaffolding and does not authorize publication.

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
The publisher also removes shell/Git startup and configuration overrides,
caller-chosen GitHub CLI configuration roots, inherited HTTP(S)/SOCKS proxy
variables, and custom TLS trust-bundle variables from child-process
environments. In particular, an attacker-supplied `HTTPS_PROXY`,
`GH_CONFIG_DIR`, or `SSL_CERT_FILE` cannot redirect authenticated repository
metadata checks or private Git blob requests. For each authenticated `gh api` request, it also creates an ephemeral
private HOME/XDG configuration root, preventing an inherited `HOME` from
selecting a caller-controlled `~/.config/gh/hosts.yml` credential profile.
The temporary root is cleaned up even on request failure. On supported
Linux runners the parent is pinned to a system-owned or runner-owned sticky
`/tmp` directory, rather than caller-controlled `TMPDIR`/`TMP`/`TEMP`.
Missing, non-directory, non-sticky, or unexpectedly owned `/tmp` must deny
GitHub API access before spawning a subprocess. In addition, the verified
child environment discards unreviewed `TMPDIR`, `TMP`, and `TEMP`; after
checking the parent, authenticated GitHub CLI requests receive
`TMPDIR=/tmp`. This keeps their ordinary scratch files out of
caller-chosen paths, not only the private credential HOME. GitHub CLI must
authenticate through noninteractive runner-supplied credentials; an interactive
or HOME-backed CLI login is no longer sufficient.
The publisher resolves child executables from system directories. These checks
mitigate ambient environment injection; the invoking runner/host, system
trust store and system executable directories must themselves be trusted. Networks requiring an explicit proxy
must establish a separately reviewed, trusted egress design rather than
passing unreviewed environment overrides. Do not execute the transfer from
an untrusted PR-controlled environment.

After the independent bootstrap has fixed the executed publisher bytes, the
publisher requires its associated local checkout to be an **unmodified Git
checkout of the same protected source-main commit** reported by GitHub. It checks the actual Git
repository root and immutable `HEAD`, then performs descriptor-relative,
no-symlink byte hashing of every trusted script and policy file against
its exact `HEAD` Git blob and mode, before reading private snapshot files.
Git `status --porcelain` is insufficient: `skip-worktree` and
`assume-unchanged` index flags can make altered executable bytes appear
clean. The verifier bypasses Git replace-object mappings and does not trust
the index as proof of worktree contents. Combined with the external bootstrap,
an untrusted compiler PR checkout cannot cause its local altered publisher
bytes to execute or grant itself approval. Prepare the exact compiler PR snapshot as
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
`main` must include a security-reviewed `commitSHA treeSHA destinationNumericID carrierMainCommit carrierRootTree`
tuple for this one transfer. The carrier commit/tree pin is part of the source-side
authorization, not merely runtime metadata. Approving a destination alone does **not**
authorize arbitrary private actor branches or source snapshots. The publisher
checks this tuple before reading any source, validates its Git blob against
the served bytes, and rechecks the approved tuple and unchanged policy SHA
before each write. The destination carrier must independently approve the
same source commit/tree/source-repository-ID tuple plus the reviewed carrier
repository ruleset ID in
`.github/private-oreslang-approved-source-snapshots.txt` on protected carrier
`main`; that carrier ledger and ruleset are revalidated before every Git write.
All security-sensitive source-side content reads (destination policy,
source-snapshot ledger, CODEOWNERS and CODEOWNERS diagnostics) are fetched with
`?ref=<authenticated-source-main-commit>`, never a floating `main` ref.
Carrier CODEOWNERS, its diagnostics, and the carrier source-approval ledger are
likewise fetched with `?ref=<approved-carrier-main-commit>`. A missing,
unmerged, altered, revoked, or commit-mismatched tuple denies publication;
authorization cannot come from the working PR branch.

The source-snapshot ledger starts **empty**. To grant one approved run,
maintainers must separately review and merge one exact
`<commitSHA> <rootTreeSHA> <numericDestinationID> <carrierMainCommit> <carrierRootTree>`
entry on protected source main. This applies even after the destination allowlist has been approved.
Keep the exact source PR head immutable through verification; any new source
commit requires a new reviewed ledger entry. Likewise, any change to the
protected carrier main commit/root tree—including CI workflow changes—requires
fresh source-side approval before another source byte can be read or uploaded.

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
checks whose contexts are bound to explicit GitHub App IDs, resolved review
conversations, at least one approving reviewer, code-owner review,
stale-review dismissal, no reviewer bypass allowances, and explicit denial of
force pushes and branch deletion.
Missing/denied GitHub protection responses fail closed **before reading source**.
The carrier must also have a valid protected-main `.github/CODEOWNERS`.
Its final three non-comment rules must exactly own, in order,
`/.github/private-oreslang-approved-source-snapshots.txt`,
`/.github/CODEOWNERS`, and `/.github/workflows/` with `@ORESoftware`.
The source-bearing branch name is fixed to
`ci/oreslang-<full-source-commit-SHA>-<reviewed-carrier-main-SHA>`.
The immutable ref therefore names both approved revisions. A later,
independently reviewed carrier revision can test the same unchanged source
without deleting or reusing an older immutable ref, and carrier CI can require
the snapshot commit's sole parent to equal the SHA encoded in the ref.
The carrier approval row also names the exact repository ruleset ID protecting
that namespace. The publisher requires an active repository branch ruleset
with no bypass actors, an exact include of
`refs/heads/ci/oreslang-*`, no exclusions, a deletion restriction, and an
update restriction with fetch-and-merge updates disabled. A creation
restriction is rejected so the reviewed publisher can create the one initial
snapshot ref; every later update or deletion is denied by the ruleset. The exporter
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

### Carrier execution-isolation gate (2026-10-19)

Main-branch CODEOWNERS approval alone does **not** protect a newly created
`ci/oreslang-*` branch from later pushes, force updates, unsafe dispatches, or
privileged workflow execution on that branch. The production publisher now
requires the carrier approval row to bind an active repository ruleset that
makes exactly `refs/heads/ci/oreslang-*` immutable after initial creation:
no bypass actors, no deletion, and no update (including fetch-and-merge
updates). It revalidates that exact ruleset before every destination write.
Carrier CI additionally binds the source-bearing ref to both the exact full
source commit and the reviewed carrier-main commit. Production Java verification
is **not** triggered by a workflow definition read from the source-bearing
branch. Instead, a `workflow_dispatch` definition on trusted carrier `main`
first asks GitHub to prove that its exact `GITHUB_SHA` is still the **current
protected main tip before the private snapshot checkout occurs**. It then
checks out the snapshot by exact commit SHA, repeats the live-main/protection
proof after checkout, requires the snapshot's sole parent to equal that
trusted-main SHA, runs the CODEOWNED
`scripts/verify-oreslang-private-ci-snapshot.sh`, and confirms through the
GitHub API that the named immutable ref still resolves to the same snapshot
commit before any Maven command executes. This rejects stale
`workflow_dispatch` reruns whose historical carrier-main authorization has
since been revoked. Generic carrier CI and secret-bearing live workflows also
exclude `refs/heads/ci/oreslang-*`; the dedicated gate is the only reviewed
workflow allowed to execute those source-bearing refs.

Only that trusted verification step receives `github.token`, solely for the
read-only ref lookup. The Java build/test jobs receive no explicit
`GH_TOKEN`/`GITHUB_TOKEN`, disable persisted checkout credentials, re-check
the exact snapshot commit and source subtree, use an ephemeral Maven repository
under `runner.temp`, and do not use cross-run Maven caches.

These checks remain **non-authorizing** until an administrator/security owner
proves the ruleset and protected carrier main are actually enforceable on the
private repository. Current plan/API limitations still make that repository-
level proof fail closed. Before authorizing any private source transfer, the
security owner must:

1. Prove effective private carrier branch/ruleset protection for the CI ref
   namespace or use an independently reviewed execution design that prevents
   arbitrary actors from modifying approved source refs. The workflow must
   checkout a fixed carrier commit SHA, never a movable branch alone, and
   revalidate the nested source Git-tree SHA before compiling or loading code.
2. Audit **every** carrier Actions workflow and trigger, including
   `workflow_dispatch`, scheduled jobs, PR triggers, artifact upload and
   inherited organization/repository secrets. A branch-local manual dispatch
   or build step can execute different code from the reviewed `main` workflow.
   Avoid transferring source into a carrier exposing unreviewed privileged
   credentials or workflows to the source-bearing branch.
3. Prove the actual source-testing workflow is read-only, minimally permissioned,
   immutable-SHA-bound, and incapable of running attacker-modified code with
   private secrets. Verify source-tree equality as the very first source-touching
   step and prove cache/artifact/log retention and cleanup policy.
4. Verify the carrier's **merged** `.github/CODEOWNERS` exists on protected
   `main`. A proposed CODEOWNERS PR without an enforced merge gate is not enough.

#### CI-ref ruleset admission proof required before publication

The draft publisher now implements this admission rather than merely
documenting it. Carrier approval rows use:

`<sourceCommit> <sourceTree> <sourceRepoID> <carrierRulesetID>`

Before reading source bytes, and again before every destination Git-object
write, the publisher fetches that exact repository ruleset and requires all of
the following:

- repository identity and exact positive ruleset ID;
- `target=branch` and `enforcement=active`;
- repository-owned source matching the approved carrier;
- an empty bypass-actor list;
- exactly `refs/heads/ci/oreslang-*` as the included ref pattern and no
  exclusions;
- exactly one `update` restriction with
  `update_allows_fetch_and_merge=false`;
- exactly one `deletion` restriction;
- no `creation` restriction, so only the initial reviewed publisher create
  is possible before the ref becomes immutable.

The publisher then queries GitHub's active-rules endpoint for the **exact**
percent-encoded `ci/oreslang-<source>-<carrier>` branch name (the branch does
not need to exist yet), explicitly requesting 100 entries from page 1. A full
100-entry page is treated as incomplete evidence and denied rather than
silently ignoring a later page. It requires that the reviewed repository
ruleset ID actually contributes one immutable `update` rule and one
`deletion` rule to that exact ref. Effective rule types are fail-closed:
only `update`, `deletion`, and `required_linear_history` are currently
audited/accepted. `creation`, signature requirements, PR/deployment/status
requirements, metadata/name restrictions, and any future/unknown rule type
deny publication before source bytes are read. This prevents predictable
late ref/commit rejection after private blobs or trees have already been
uploaded.

Before reading source bytes and again before every pre-ref destination write,
the publisher also queries GitHub's matching-refs endpoint for the exact
reserved branch prefix and requires an empty result. This rejects retries or
prefix collisions before private objects are uploaded. The absence check is
repeated immediately before the atomic ref-create POST. A narrow distributed
race still exists if another authorized repository writer creates the same ref
between that final GET and GitHub's create operation; the create remains
fail-closed, but already-created unreachable private Git objects can remain.
Closing that residual race requires an independently reviewed nonce/reservation
protocol or repository-lifecycle isolation rather than weakening ref
immutability.

These proofs reject a stored ruleset whose pattern/effective applicability has
drifted and reject deterministic stale-ref retries before source export.

Carrier CI independently requires the source-bearing ref to be exactly one
snapshot commit above a carrier base that did not already contain
`private-oreslang-source/`; later commits are rejected before source tests.

This does **not** make repository settings self-authenticating. Independent
administrative/security review must still establish the ruleset and protected
carrier main. Current source and Fiducia private-repository ruleset reads
return HTTP 403 because the plan feature is unavailable, so the draft
publisher necessarily fails closed and no source publication is authorized.

Source-free carrier evidence for draft
[fiducia-cloud-test/java-client-consumer#15](https://github.com/fiducia-cloud-test/java-client-consumer/pull/15):
the normal PR harness run 39315398300 passed; exact approved subtree run
39315493087 passed; tampered subtree run 39315502068 failed at the expected
verification step; and post-snapshot update run 39316715981 failed at that
same gate. These fixtures contain no Oreslang private source.
The current carrier hardening head `32cd539115355fae2c27a3d70de36a09bca511b8`
was executed by hosted Actions run 39320753716: all 15 recorded job steps
succeeded and pytest reported **18 passed in 3.28s**. Those tests execute the
same CODEOWNED local Git/ledger verifier used by the trusted-main production
workflow against source-free synthetic repositories.

Carrier PR #15 also adds the deny-by-default carrier approval ledger,
terminal CODEOWNERS, immutable action pins, and a guard that prevents
`ci/oreslang-*` refs from entering the secret-bearing `live.yml` job.
It remains draft and must not merge while carrier `main.protected=false`.

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

### Private-source execution must be networkless and host-isolated

A private repository token is not the only exfiltration channel. Even with no
`GITHUB_TOKEN`, `GH_TOKEN` or repository secrets, code under test can send
its own private bytes over ordinary outbound network access or encode them into
logs/artifacts. Therefore an approved mirror is still **not executable** on a
normal GitHub-hosted runner or directly on the carrier host.

The reviewed carrier design requires source-bearing build jobs to run only on a
disposable runner labeled `self-hosted, linux, x64, oreslang-private-ephemeral,
oreslang-egress-locked`. The source itself executes only inside a preloaded,
content-addressed Docker image approved on pinned carrier `main` as exact
`sha256:<64hex>` IDs for the Java 21 and Java 25 roles. The source publisher
requires both image IDs before reading/uploading private source and revalidates
the exact pinned policy before each destination write.

The production sandbox uses `--pull=never`, `--network=none`,
`--ipc=none`, `--log-driver=none`, a read-only container root and read-only
source bind, dropped capabilities, `no-new-privileges`, non-root UID 65532,
private bind propagation, tmpfs work/scratch, bounded PIDs/memory/CPU, swap
disabled, and a minimal environment. Maven executes with `-o` offline mode;
all required toolchains/plugins/dependencies must already exist in the reviewed
sealed image. Untrusted build stdout/stderr is discarded and every nonzero
private build exit is normalized to one generic failure result rather than
publishing the exact exit code.

Source-free carrier head `0a4b4c79d9121d3113585b583b77f1a53856f852`
passed **54/54** deterministic tests, including explicit NUL-byte rejection
for both pinned carrier policy blobs, plus the real hosted Docker isolation
probe in run `39346656825`. That probe verified the sandbox contract using
synthetic bytes only. It does **not** prove the dedicated self-hosted runner's
egress controls and does not authorize private-source transfer while protected
main/ruleset/review gates remain unavailable.

### Exact production-module verification — 2026-10-19

The current draft publisher was materialized locally from GitHub and its bytes
were independently checked with `git hash-object` against GitHub blob
`35261930af295997b8f50dda9e6224184b49e1d9`. Python 3.13
`py_compile` succeeded. Against that **exact production module**, eight
focused adversarial tests passed for immutable source/carrier revision binding,
including split-authority cases where floating `main` contains an approval
that the authenticated commit does not. A further sixteen focused admission
checks passed for the carrier source-ref ruleset and strict app-bound branch
protection, including negative bypass, broad-ref, deletion/update, creation,
source-identity, target, strictness and app-ID cases.

This is exact production-code evidence, but it is **not** execution of the
complete checked-in Python/Bash publisher suites and does not authorize source
transfer. The source organization's exact-head Actions jobs still fail before
runner allocation; the complete suite, independent review, and repository-level
protection proof remain release gates.

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

The publisher also consults GitHub's CODEOWNERS diagnostics at the exact
authenticated protected-main revision,
`GET /repos/{owner}/{repo}/codeowners/errors?ref=<reviewed-main-SHA>`, and
`GET /repos/{owner}/{repo}/collaborators/ORESoftware/permission`.
GitHub documents `ref` here as accepting a branch, tag, or commit name, so
the publisher deliberately supplies the already authenticated commit SHA.
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
may be sent inside isolated tree POSTs when `N >= 16` **and their
serialized JSON representation fits the request budget**; otherwise files
use individually verified blob uploads. Batches are bounded to at most 64
entries and 256 KiB of conservatively accounted JSON request bytes. Quoted,
backslash-heavy, control-byte and non-ASCII contents may expand during
JSON escaping by several times their original UTF-8 size. The publisher
counts the serialized `json.dumps()` representation for both file content
and path before batching; oversized inline text falls back to an individually
verified blob. This keeps every submitted tree request under the intended
bound, including singleton batches. Non-UTF-8, NUL-containing, and larger
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


On October 15, a follow-up source-free [Benefactor run #38771723972](https://github.com/benefactor-cc/backend.rs/actions/runs/38771723972)
verified the JSON-escaping fix on a private GitHub-hosted runner. It passed
three executed steps with **554 synthetic files**, including 100,000 control
characters (which serialize much larger than their raw bytes), one original
binary file and executable mode bits. Nine JSON-bounded isolated-tree POSTs
plus two separately verified blob POSTs produced Git tree
`5fbd663e02b096ca8288cab3bb71ac1634edc76a`, exactly matching independent
`git write-tree`. **11** Git-object writes were executed, without creating a
carrier branch or transferring any private Oreslang source. The full
production publisher would also need its separately authorized root-tree,
commit and temporary-ref writes. This is targeted synthetic testing, not
proof that the complete checked-in Python publisher methods or Bash suite passed.
The source organization's Actions still fails before running job steps.

## 2026-10-15 follow-up: failure injection and deterministic batching tests

The draft exporter regression suite now checks every single-file Git-object
write before creation of a CI branch, including blob, isolated source tree,
carrier root tree, and commit. Any simulated API failure must abort without
issuing a ref-creation POST. A separate ref-creation failure test rejects
success reporting. For multi-batch UTF-8 uploads, removing the carrier's
effective CODEOWNER approval after the first tree POST must deny the *next*
tree POST and leave the CI ref uncreated.

A new fixed-seed adversarial batching test exercises **180** source-free
scenarios across boundary sizes (1, 15, 16, 17, 63, 64, 65, 97 and 160
files), NUL/non-UTF-8 bytes, JSON-escaped text, emoji, oversized inputs,
empty files, and both Git executable modes. Every planned file must appear
exactly once, with identical source bytes/mode and JSON-serialized payloads
under the 256-KiB batch cap. The corresponding locally transcribed batch
planner passed **180/180** scenarios (**9,960** input files across **819**
planned batches), but this is *not* the full checked-in repository suite.
The new regression methods are committed yet require execution on the exact
source branch revision.

The source and carrier branch protections and protected-main approval files
remain independent prerequisites. No source export, private CI branch
creation or protected-branch bypass is authorized by these tests.


### Adversarial manifest isolation — source-free verification (2026-10-15)

The publisher's `source_tree_sha` helper now rejects ambiguous file/directory
collisions in **both** input orders, duplicate file paths, invalid traversal
components and invalid Git file modes. A previous direct-call sequence could
replace a nested directory with a file silently, or raise `AttributeError`
instead of a deterministic `Denied`. Ordinary on-disk directory traversal
already forbids this impossible filesystem state, but the hash helper must not
silently accept a malformed programmatically supplied manifest.

A focused isolated test of the extracted hashing function checked eight
invalid manifests, a known canonical Git-tree hash, and 30 randomized valid
trees against independent local `git write-tree`.

[Benefactor private Actions run #38807447374](https://github.com/benefactor-cc/backend.rs/actions/runs/38807447374)
**passed**, executing four job steps. Its synthetic-only manifest contract
rejected eight ambiguous paths and completed 30 randomized Git fixtures.
The same run also repeated the 554-file JSON-bounded Git API test, using nine
tree batches and two blob uploads (11 Git-object POSTs) with exact tree SHA
`5fbd663e02b096ca8288cab3bb71ac1634edc76a`.
The test did **not** transport private compiler code or execute the complete
private exporter test suite. The PR now contains 132 Python test methods
that still require an authorized trusted runner. No private source transfer
is authorized by this synthetic evidence.


### Direct Git-tree manifest path hardening (2026-10-15)

The frozen-source scanner already rejects path controls, DEL, backslashes and
non-UTF-8 names. The `source_tree_sha` helper was separately hardened to
**enforce the same restrictions for direct callers**, rather than calculating
an object digest for an impossible source path or propagating an uncaught
`UnicodeEncodeError`. Security regression coverage includes embedded NUL,
newline, carriage return, ASCII control characters, DEL, backslash, and
surrogate code points, as well as the existing file/directory collision tests.

On an isolated local synthetic fixture, eight malformed path cases were
rejected and 50 randomized valid Git tree digests matched the independent
`git write-tree` command. The [Benefactor private Actions run #38811825352](https://github.com/benefactor-cc/backend.rs/actions/runs/38811825352)
**passed all four job steps** on head `a75bee90f1f39ae4b2bc422e1b47333f6d184471`.
Its logs confirmed rejection of 15 malformed synthetic manifests, 60
randomized Git fixtures, and the repeat 554-file JSON-bounded Git API tree
test (nine tree requests plus two blob uploads, 11 Git-object POSTs total,
tree SHA `5fbd663e02b096ca8288cab3bb71ac1634edc76a`).
No compiler source was copied, mirrored or executed on that carrier.

The Python publisher security suite now contains **134 test methods**.
The complete Python and Bash suites remain unexecuted because source
GitHub Actions jobs still terminate before runner allocation. Synthetic
carrier success is not a substitute for the exact private-source security
suite or approval for any source transfer.


### Git branch namespace preflight (2026-10-15)

The publisher now rejects Git-invalid isolated branch names **before**
reading protected policy data or source bytes and before issuing any Git
object write. The format allowlist `ci/oreslang-...` alone previously
permitted `a..b`, `a.lock`, or `a.` suffixes, all of which
`git check-ref-format --branch` rejects. The early check now enforces the
Git prohibitions on adjacent dots, trailing dots and the `.lock` suffix.
It also retains the narrower publisher namespace of exactly one branch-name
component after `ci/`, although Git itself permits more components.

Two offline regression methods cover six invalid ref spellings, three
valid spellings, and deny-before-policy-read behavior; the checked-in
security suite has **136 Python test methods**.

The source-free [Benefactor private Actions run #38812541918](https://github.com/benefactor-cc/backend.rs/actions/runs/38812541918)
**passed all five job steps**. The dedicated validation step exercised
**2,000 deterministic Git ref-format fuzz cases**, nine explicit denied
publisher spellings, and three accepted spellings. Its earlier steps also
repeated the 554-file JSON-bounded Git-object batching proof (nine tree
POSTs, two blob POSTs, exact Git SHA
`5fbd663e02b096ca8288cab3bb71ac1634edc76a`) and 15 rejected
malformed synthetic manifests with 60 randomized tree fixtures.

An initial version of the carrier test compared against Git without
accounting for the publisher's deliberately narrower namespace; Git permits
`ci/oreslang-a/b`, whereas the publisher does not. That **test
assumption** caused run #38812453359 to fail. It was corrected in carrier
commit `d145ed4788e55977459c769bfb9cccdd92895d7b`, and the
subsequent live run #38812541918 passed. No private compiler files were
transferred in either run.

These are synthetic-only checks of policy shape and Git behavior. The full
source-side Python and Bash exporter suites remain unverified until a
trusted runner executes their complete checked-in revisions. Neither
synthetic carrier availability nor this PR authorizes private source
mirroring without reviewed immutable source/destination approvals and
enforced protected branches.


### 2026-10-19 destination Actions posture and retention gate

Before reading private source, the publisher now independently queries repository
Actions administration settings and fails closed unless all of the following are
true:

- private-repository forking is disabled;
- Actions is enabled but third-party Actions are not globally allowed;
- a `selected` Actions policy permits GitHub-owned Actions only, with verified
  publishers disabled and no extra patterns (or the stricter `local_only` mode);
- the default `GITHUB_TOKEN` permission is read-only and workflows cannot approve
  pull requests;
- artifact/log retention is explicitly readable and no greater than 7 days.

The posture is fingerprinted before source collection and rechecked during
per-write authorization. Any setting drift aborts the next write. API-budget
reservation includes these additional authenticated reads.

Source-free runner evidence only: dummy carrier PR #33, head
`812d20defda9a1e2a10d38e566e25f472c7c08ab`, produced Actions run
`39343253672`; its `verify` job executed checkout, Node contract tests and
shell syntax steps successfully. This proves runner capacity only. The dummy
carrier's `main` currently reports `protected=false`, and repository rulesets
are unavailable on its current plan, so it remains ineligible for private
Oreslang source transfer.

Exact source PR #954 head after the code/test hardening still receives source-org
workflow startup failures. Run `39343397605` concluded failure with the
individually queried `preflight-regressions` job returning `steps=[]`. That is
an observed no-step failure, not execution of the new Python regressions.
