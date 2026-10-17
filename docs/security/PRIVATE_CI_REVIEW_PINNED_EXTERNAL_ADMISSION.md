# Review-pinned external admission for private CI

Status: **candidate security fallback; no export authorized until independent approvals exist.**

This mode exists because the current GitHub plan does not provide branch
protection or repository rulesets for these private repositories. It does not
pretend an unprotected branch is protected. Instead, it removes mutable branch
state from the authorization decision.

## Independent reviewer policy v2

The reviewer is not hard-coded to an account. Authorization reads
`.github/private-ci-independent-reviewer.json`, which is **disabled by
default**. Enabling it requires a separately reviewed policy change that names
one exact GitHub user login + immutable numeric user ID.

The configured reviewer must:

- not match any operator-controlled identity listed in
  `disallowedReviewerIds`;
- be a distinct GitHub `User`, and never the author of any security PR being
  approved;
- have live repository permission at or above `minimumPermission` (currently
  `write`) in **both** the source and carrier repositories, verified from
  fresh GitHub collaborator-permission responses;
- approve each security PR at its exact current head; a later exact-head
  `CHANGES_REQUESTED` revokes an earlier approval;
- author the one exact state-bound incident-resume line used for this frozen
  manifest.

A second account controlled by the same operator is explicitly insufficient.
Until a genuinely distinct reviewer is provisioned and the policy is enabled,
the verifier fails closed and private-source export remains impossible.

## Trust anchors

A private-source payload may be created only when an external trusted
dispatcher proves all of the following from live GitHub API responses:

1. publisher policy PR `oreslang-source.java#1123` is open and the latest
   review on its exact current head by the explicitly configured independent
   reviewer is `APPROVED`; reviewer login and numeric GitHub user ID must
   match policy;
2. Sagitta destination approval PR `#1092` satisfies the same exact-head
   review rule;
3. exact source snapshot approval PR `#1189` satisfies the same exact-head
   review rule and contains exactly one active
   `source-commit source-tree destination-repository-id` tuple;
4. Sagitta carrier policy PR `sagitta-infra#7` satisfies the same exact-head
   review rule;
5. incident `oreslang-platform-conformance#22` has an ordered authorization
   history from that same reviewer whose latest state is
   `PRIVATE_CI_RESUME_APPROVED`; `PRIVATE_CI_RESUME_REVOKED` immediately
   revokes authorization until a later bound re-approval;
6. the approved source commit exists in repository ID `1400792274` and its
   root Git tree equals the approved source tree;
7. destination repository ID `1357615104` is still exactly
   `sagitta-stack/sagitta-infra`, private, active, non-archived and
   non-disabled;
8. the reviewer policy explicitly disallows operator-controlled GitHub IDs
   `11139560` and `2148448`, and live collaborator-permission responses
   show at least the reviewed minimum permission on both source and carrier;
9. the exact reviewer-policy bytes are bound into the authorization receipt by
   Git blob SHA;
10. the exact carrier validation workflow bytes are read from the independently
   approved carrier PR head and their Git blob SHA is recorded.

The canonical authorization receipt uses schema v2 and records reviewer login,
numeric GitHub ID, reviewer-policy blob SHA, and both live permission levels.
A different username alone does not establish independent review.

The source-side read-only checker
`scripts/verify-review-pinned-private-ci.py` validates these inputs and emits
a canonical receipt. It never writes Git objects, creates refs, or dispatches
CI.

## Payload construction

The dispatcher creates a **new** branch only if
`ci/oreslang-<source-tree-sha>` does not already exist. Reuse, force-update or
branch-name substitution is denied.

The payload commit has exactly one parent: the independently approved exact
carrier PR head. Its diff from that parent may change only:

- `private-oreslang-source/**`, whose subtree Git SHA must equal the approved
  source tree; and
- `.private-ci-provenance.json`, containing the canonical authorization
  receipt fields needed by the carrier workflow.

No workflow, CODEOWNERS, script or other carrier file may change in the
source-bearing payload commit.

Immediately before every destination Git-object/ref write, the dispatcher
rechecks destination numeric repository ID and private/active status. This
retains the existing publisher's fail-closed confidentiality checks. As with
all GitHub API publication, repository administrators remain part of the
confidentiality trust boundary: no branch protection setting can prevent an
organization owner from changing repository visibility later.

## Execution binding

The carrier validation workflow is **push-only** on
`ci/oreslang-<40-hex-tree>`. It has no `workflow_dispatch` source/tree
inputs.

The workflow:

- requires repository ID `1357615104` and private visibility in the push
  event;
- checks out exactly `github.sha`;
- requires `HEAD == github.sha`;
- derives the expected source tree from the branch name;
- requires `HEAD:private-oreslang-source` to equal that tree;
- requires the payload commit's diff from `HEAD^` to contain only the
  private-source subtree and provenance record;
- validates strict provenance schema, repository IDs, exact SHA syntax, exact
  parent SHA and approval/review IDs before executing source;
- uses read-only `GITHUB_TOKEN` permissions and SHA-pinned third-party actions.

The separate lightweight carrier-policy workflow also runs on payload pushes.

## Evidence acceptance

A green GitHub check is not sufficient by itself.

The external dispatcher accepts evidence only if the run:

1. belongs to repository ID `1357615104`;
2. has event `push`;
3. has `head_branch == ci/oreslang-<approved-source-tree>`;
4. has `head_sha` exactly equal to the payload commit created by the
   dispatcher;
5. has the independently approved carrier head as its sole parent;
6. still reads back the exact approved source subtree tree SHA;
7. completed the expected validation workflow with real executed steps and no
   skipped/failing required phase.

Any run on another SHA, a later branch update, a manually dispatched workflow,
or a user-created lookalike branch is ignored.

## Why branch protection is not the root of trust in this mode

Unprotected branches allow a writer to create, move or delete refs. They do
**not** allow mutation of an existing Git commit object.

This mode never authorizes "whatever is currently on main" or "whatever is
currently on the CI branch." Authorization is attached to immutable reviewed
PR head commits and the exact immutable payload commit. A malicious later
branch movement can create a different run, but cannot change the already
recorded authorized commit. The external verifier rejects the different SHA.

This is not identical to protected branches. It moves enforcement from GitHub
merge policy to an external admission/acceptance boundary. If private branch
protection or rulesets become available later, enable them as defense in depth
without weakening any exact-head checks.

## Remaining independent gates

This fallback becomes usable only after:

- all four exact-head independent approvals exist;
- incident #22 has the explicit independent resume comment;
- the carrier workflow/security changes themselves have been independently
  reviewed;
- the read-only verifier and publisher/admission tests execute successfully on
  a trusted runner;
- the remaining historical exposure cleanup is accepted by the incident owner.

Until then, export remains denied.
