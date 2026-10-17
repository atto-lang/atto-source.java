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
  --source-commit "$PINNED_SOURCE_COMMIT_SHA" \
  --branch "ci/oreslang-$PINNED_SOURCE_GIT_TREE_SHA"
```

The checked-in policy file must explicitly list the destination as `owner/repo <numeric-id>` (one ASCII space; positive decimal ID without leading zeros). The Bash preflight and Python publisher independently fetch the live GitHub numeric repository ID and require an exact match with the independently reviewed protected-main policy before the first blob upload and before every subsequent GitHub API write. A renamed or recreated repository cannot inherit approval by reusing its slug.
The publisher must also fetch the **origin source repository's protected main**
from GitHub, verify that the local approval and publisher/guard scripts are
byte-for-byte identical to **one immutable protected-main commit SHA**, and
reject if branch protection is not active or `main` moves during verification.
The exporter rechecks protected-source authority immediately before the
isolated destination branch is created and rejects any approval-commit drift,
even if individual policy files were unchanged.

The protected `.github/CODEOWNERS` file must also be verified byte-for-byte
against the same immutable, reviewed `main` commit. It must end with the
nine exact `@ORESoftware` ownership rules for the private-source approval,
publisher, guard, workflow, security documentation, tests and CODEOWNERS
itself. This accounts for GitHub's last-matching-rule behavior: a later
wildcard or explicit exception would otherwise override a supposed
security-owner rule. Missing, reordered, replaced, or overridden rules deny
export before any private source blob is uploaded. Comments after the final
security rules do not change effective ownership.

A GitHub `protected=true` branch is **not sufficient**: the exporter also
requires readable, enforceable branch-protection details, at least one
nonstale mandatory code-owner approval, administrative enforcement, strict
nonempty required CI checks, and disabled force pushes/deletions. Explicit
pull-request review bypass allowances are rejected. These settings are checked
before reading private test inputs and again after validating protected-main
policy blobs; inability to read the protection API is a hard denial, not a
fallback to the summary `protected` flag. Existing required check names must
still be backed by separately verified runner-executed work (not zero-step
GitHub job failures). Branch-protection verification alone does not certify
that a chosen required workflow actually compiles the language. This is in addition to the
separate required independent review;
a developer cannot approve the destination by editing their PR checkout.

The caller must pin an immutable origin source **commit**; the publisher
independently obtains that commit's Git tree SHA. It computes the Git tree
of the collected regular-file source snapshot (including nested names and
executable modes) before any blob upload, requires a byte-identical match
with the origin commit, and requires an isolated branch named exactly
`ci/oreslang-<40-lowercase-character-source-tree-sha>`.
It compares the actual newly created isolated destination subtree SHA to
the expected source SHA before creating any destination commit or ref.
The origin protected-main requirements and code-identity checks also mean
a hardening PR cannot authorize itself merely by containing this code.

**This is not yet authorized:** as of the audit, source `main` reports
unprotected and the protected-main destination allowlist is empty.
Do not run the exporter until an independent security review approves a
protected-source policy, and do not interpret an unreviewed local checkout
as authority to transfer private source.

 The
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

## Administrative activation order and independent sign-off

The following gates are **sequential**, not interchangeable. This document,
a draft PR, a CI canary, and an approved destination name in an unprotected
checkout **cannot** authorize source export.

1. An administrator must configure effective protection for the private source
   repository's exact `main` branch, using an organization plan that supports
   private-repository protection. Require PRs, independent CODEOWNER approval
   from `@ORESoftware`, dismissal of stale reviews, enforced admin coverage,
   strict/nonempty actual CI checks and prohibition of force pushes/deletions
   and review bypass. The publisher's authenticated `gh` credential must be
   able to **read** the complete branch-protection API result; the browser
   reporting a protected branch is insufficient.
2. Prove enforcement with a benign test PR: missing code-owner approval,
   stale review after a commit, absent required CI, and zero-step CI must all
   block a merge. A successful step-executed test is needed for positive proof.
   Record source/head/status identities. See
   [main-protection tracker](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/994).
3. Independently review and run the offline Bash/Python security suite for
   publisher-hardening PR #1118. Merge this hardening **first** after the
   branch-protection gate is real; the previously deployed publisher must not
   gain an approved destination by merging the allowlist earlier.
4. Separately obtain independent CODEOWNER review of destination allowlist PR
   #1085 and merge it into **protected main** only after the hardened publisher
   is merged. Approval must pin the immutable numeric ID as well as the slug,
   e.g. `owner/repo 1234567`. The currently proposed destination has GitHub
   repository ID `1411109719`, subject to **fresh** private-visibility and
   ID verification before publication.
5. Only then may the reviewed protected-main publisher prepare the exact
   immutable source tree, check authorization before each private Git write,
   and use the approved private runner. Compare the source and destination
   subtree SHA, inspect actual job steps and artifacts, and handle retained
   Git objects as confidential even if a destination branch is deleted.

The GitHub App connector may have source-repository content rights without
repository-administration rights. A `403 Resource not accessible by integration`
from the protection endpoint **blocks publishing**; it must not trigger a
fallback to a less-protected policy. Private-repository plan limitations
must be addressed by an appropriate eligible plan, never by making the source
public.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
