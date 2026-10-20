# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo` destination through a
reviewed, protected-branch change.

An allowlist line is necessary but no longer sufficient. The protected
`.github/private-ci-carrier-contracts.json` must independently pin the live
destination repository ID, an exact reviewed source-free carrier `main`
commit, the carrier workflow blob, and the exact reviewed test-plan blob/PR
set. A carrier `main` advance, repository delete/recreate, workflow change,
or test-plan change fails closed until the source policy is reviewed again.

**Origin protection is mandatory.** Before reading private source files, the
publisher now checks the live GitHub identity of
`ores-truffle-oreslang/oreslang-source.java`, verifies that its official
`main` branch reports `protected: true`, and reads branch-protection rules
from GitHub. It requires `required_pull_request_reviews.require_code_owner_reviews`,
`required_pull_request_reviews.dismiss_stale_reviews`,
at least one required approving review, and `enforce_admins.enabled` to be
true. Force pushes and branch deletions must be explicitly disabled, and any
pull-request review bypass allowances are rejected. Missing or unreadable rules fail
closed, even if GitHub reports `protected: true`. The publisher pins the
policy read to that branch's Git commit and compares its policy bytes
with the local checkout policy. V3 also binds the executing destination guard,
launcher, legacy publisher, and V3 publisher byte-for-byte to that same
protected-`main` commit, and requires the executing Python modules to be the
exact files beneath the supplied trusted checkout. A copied or locally modified
dispatcher therefore cannot borrow authorization from genuine protected policy
bytes.
It repeats these checks immediately before publishing the new CI ref. Merely
adding the allowlist line to an unreviewed checkout does not authorize export,
and a CODEOWNERS file without enforced branch protection is not sufficient.
**At the 2026-10-19 audit, GitHub reported `main protected: false`, so the
publisher deliberately fails closed until administrators enable protection
and verify that CODEOWNER reviews are required.** GitHub returned a 403
for ruleset inspection on the available repository plan, so this tool session
could not independently establish an equivalent enforced ruleset.

Until that prerequisite is met, do not mirror private blobs even if an
allowlist pull request is approved. Source-free test suites are permissible.


**Destination protection is independently mandatory.** Before reading source
bytes, v3 also verifies that the private carrier's live `main` commit equals
the immutable reviewed `base_sha` and that its branch protection API requires
CODEOWNER approvals, stale-review dismissal, at least one approval, administrator
enforcement, no review bypass actors, and no force pushes or branch deletion.
The carrier's reviewed base must contain an authenticated `.github/CODEOWNERS`
blob with the exact effective rules `* @ORESoftware`,
`/.github/ @ORESoftware`, and `/.github/CODEOWNERS @ORESoftware`, in that
order. Both protection and CODEOWNERS checks are repeated through the existing
`checked_destination` gates before source-bearing refs are made executable.
Missing GitHub branch-protection API access or an unprotected carrier main
must fail closed, regardless of Actions minutes or successful source-free tests.

Do **not** accept a CI destination allowlist from environment variables or
unreviewed workflow inputs: a caller must not be able to grant its own approval.
Run the checked transfer from trusted, reviewed source, not an untrusted PR
checkout. If an approval policy can be modified by the code under test, treat
the result as untrusted.

## Diagnostic checker is not authorization

`scripts/check-private-ci-destination.sh` is a convenience diagnostic only.
Its output must never authorize a source transfer. The v3 publisher does not
execute it during preflight; authorization comes from data-only local policy
validation plus the authenticated protected-main policy/carrier contract and
live destination checks. Do not grant privileged reusable credentials to the
diagnostic script.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. Direct execution of `scripts/publish-private-ci-source.py` is
fail-closed; that legacy module exists only to provide audited helper functions
to v3. V3 accepts an approved destination, a bounded local source
directory, the exact open source PR head, and one test plan already pinned by
the protected carrier contract:

```bash
scripts/run-in-private-ci-destination.sh opto-sync-test/go-java-validation \
  --source-dir ./verified-source-tree \
  --source-commit FULL_40_CHARACTER_OPEN_PR_HEAD_SHA \
  --source-pr 1482 \
  --test-plan ci/test-plans/java-pr1482.json
```

The caller does not choose the executable carrier branch. After proving the
complete source Git tree, the dispatcher derives exactly
`ci/oreslang-<SOURCE_ROOT_TREE_SHA>`. The exact source commit must also be
the current, non-draft head of the named open PR against source `main`. V3
requires an independent `@ORESoftware` CODEOWNER review whose latest review
state is `APPROVED` for that exact HEAD commit; stale, dismissed, prior-commit,
self-authored, missing, or pagination-ambiguous review evidence fails closed.
V3 validates the PR/head relationship and current exact-HEAD approval before
reading/uploading the source and again immediately before creating executable
carrier refs, so a PR that advances, loses approval, becomes draft, or closes
during the transfer fails closed.

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
The v3 dispatcher creates a canonical `ores-private-ci-receipt-v3` that
binds source repository ID, PR number/head SHA/root-tree SHA, manifest SHA-256,
destination repository ID and reviewed base commit, immutable carrier workflow
blob, reviewed test-plan digest, and a 24-hour expiry. The receipt digest is
bound into the carrier commit message. It then creates a **new**
`ci/oreslang-<SOURCE_ROOT_TREE_SHA>` ref and the exact
`ci/authorized-<CARRIER_COMMIT_SHA>` dispatcher authorization ref required
by the carrier. Before either create, v3 proves that **both** deterministic
handoff ref names are absent. This establishes rollback ownership: an ambiguous
create failure must never cause cleanup to delete an identical ref that existed
before the invocation. Existing branches, including `main`, are never updated
or adopted.

The reviewed carrier base must itself be source-free: neither
`private-oreslang-source/` nor `ci/oreslang-receipt.json` may already be
present. The source-bearing commit may change only that source subtree plus
the receipt; carrier workflow and test-plan bytes remain inherited from the
pinned base.

An explicitly supplied `--source-commit` now pins the **exact original
private Git commit**. Before uploading, the publisher retrieves that commit's
source-tree hash and its complete, non-truncated recursive tree from the
official origin. It rejects untracked/dirty/missing local files, differing
executable modes, symlinks/submodules, or an incomplete tree. Every local
path/mode/blob hash must match the origin tree exactly. After uploading,
the newly assembled isolated subtree must have the **same Git tree SHA**
as the verified original, before the private destination's root commit/ref
can be created. No transfer is authorized merely because a filesystem
directory has a trusted-looking name.

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
Authenticated GitHub CLI subprocess output is now OS-bounded **before**
the publisher reads it: on the supported POSIX/Linux execution path, stdout
and stderr are redirected to separate anonymous temporary regular files,
and the child has a pre-exec `RLIMIT_FSIZE` of 24 MiB per stream. Responses
that reach the cap, time out, are non-UTF-8, or cannot be securely limited
are rejected without logging payloads. This is a per-stream byte cap, not a
total child-disk-I/O or memory limit. Both legacy authenticated API calls
and v3 carrier ref queries use this boundary. HTTP 422 ref-create conflict
and HTTP 404 ref-absence decisions require canonical first-line `gh`
status messages; incidental status substrings are not trusted. Source-free
regressions exercise actual child writes to both descriptors, decoding,
timeouts, and spoofed status lines. **A passing focused reproduction is not
a passing full publisher regression or compiler CI run.**

GitHub exposes no API to delete individual blobs, trees, or commits. Therefore
any failure after the first private Git-object upload is a **retention
incident**, even when every handoff ref is successfully rolled back. The v3
publisher tags that failure, the CLI emits `PRIVATE_CI_RETENTION_INCIDENT`,
and exits with status 2.

After a retention incident:

1. Stop retries and do not reuse the carrier for another private-source handoff.
2. Keep the repository private and restrict access; never make it public/internal
   to "clean up" the failed run.
3. Record the carrier repository ID, attempted source PR/head, UTC time, and
   operator/run identity in the private incident record. Do not paste source
   bytes or compiler output into the ticket.
4. Have an administrator determine the repository-level retention response.
   For a dedicated disposable carrier, prefer quarantining and replacing it;
   if retained Git objects must be purged, engage GitHub Support because deleting
   refs alone does not prove object deletion.
5. Review and repin the protected carrier contract to a clean replacement before
   any later transfer. The ordinary publisher must not treat successful ref
   rollback as proof that the destination is clean.

A normal destination-rejection exit (status 1) is only safe before private
object upload. A retention-incident exit (status 2) requires the procedure
above before reuse.

It does **not** intercept arbitrary connector/API tool writes, cannot
completely prevent visibility changes between API checks, and cannot
guarantee that the source directory itself is free of secrets. Run only
from an authorized protected checkout using narrowly scoped credentials.
There is no permission to use a public repository when CI minutes run out.

Test without network access or sending any source:

```bash
bash scripts/test-private-ci-destination.sh
python3 scripts/test-publish-private-ci-source.py
python3 scripts/test-publish-private-ci-source-v3.py
```

The Bash test uses a temporary policy checkout; Python tests mock all GitHub
API operations. V3 tests additionally prove protected carrier-contract parsing,
source-free base enforcement, exact open-PR binding, receipt construction,
tree-derived branch naming, and dispatcher-authorization ref creation. The
real allowlist remains empty and **no destination is authorized** unless
separately reviewed and protected-main enforcement is active.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.

