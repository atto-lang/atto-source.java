# Atto Java private CI recovery — security decision record (#65)

**Status: DENIED / PENDING INDEPENDENT SECURITY REVIEW.** This document is
an operator runbook and a proposed decision record, **not** a source-export
permission grant, branch-protection attestation, or CI pass. It does not change
the allowlist, carrier contract, privileged publisher, or GitHub settings.

- Tracking: [atto-source.java issue #65](https://github.com/atto-lang/atto-source.java/issues/65)
- Baseline checked: Atto Java `main` `9caccb4ac57dbbe9cfd13cb490a005e273d217e5`, after merged PR #61.
- Imported authoritative controls: `docs/security/CI_PRIVATE_SOURCE_MIRRORING.md`,
  `scripts/publish-private-ci-source-v3.py`,
  `.github/private-ci-approved-repositories.txt`,
  `.github/private-ci-carrier-contracts.json`, and `.github/CODEOWNERS`.
- Organizational review policy: `atto-lang/.github/AGENTS.md` (Ready clock,
  independently authenticated reviews, CODEOWNERS, exact-head CI, and merge holds).

## Current facts, not permissions

At this baseline, GitHub reports `main.protected = false`. Its private
rulesets endpoint reports a plan restriction and the integration cannot read
branch-protection settings. The reviewed private-destination allowlist has
**no active entries**. The destination `opto-sync/atto-ci` is private
(repository ID `1458322748`), but **not allowlisted**, has **no matching V3
carrier contract**, and its default branch is **not `main`**. Do not infer
approval from the destination's privacy, test successes, Git object hashes,
the user's chat authorization, or GitHub's `mergeable` flag.

Private CI runs completed before this review, including
[combined-snapshot run #44799133349](https://github.com/opto-sync/atto-ci/actions/runs/44799133349)
for Java PRs #58, #53, #52 and #51. Those are real technical test receipts
but they do **not** constitute V3-compliant export, a source-repository
required status check, or a full migrated-`main` build. The combined run
reconstructed a verified sparse dependency closure, not the entire imported
repository. Preserve its provenance while avoiding any representation
that the tests or the earlier transfer were security-approved.

## Mandatory stop conditions

Until an independently authenticated security owner rules otherwise,
**treat the V3 policy as governing this repository's cross-repository
private-source transfer workflows**. No new private-source transfer to any
carrier, token-free or otherwise, unless the exact reviewed policy permits
it. Do not use an ad hoc GitHub API/connector, a temporary branch, a raw
Git blob, an Actions artifact, or a source-free workflow as an alternate
permission path. Do not put private source in logs, comments, public
repos, or unreviewed caches. Deleting a Git ref does **not** erase
underlying Git objects.

A GitHub branch called `main` is not by itself protected. V3 requires
authenticated live branch protection, independent CODEOWNER approval,
stale-review dismissal, strict non-empty required checks, admin
enforcement, no review bypass, and no force-push or deletion. A draft PR
or CODEOWNERS text is not a substitute. If the private GitHub plan does
not support enforcement, **hold private exports** pending a supported
plan or a different authorized design; never make the repo public merely
to obtain Actions minutes.

## Security-owner decision required

The independent `@ORESoftware` reviewer and repository administrators
must record in #65:

1. **Applicability.** Does the imported Oreslang V3 private-source
   export control apply to all Atto Java cross-repository transfers?
   Until an approved change clarifies it, operate fail closed. Review
   any proposed scope change as a security policy change, not an
   interpretation made solely by the test operator.
2. **Source protection.** Enable and read back real `main` protection
   with all V3 properties listed above. Resolve GitHub plan/permission
   limitations with the repository administrator; never claim these
   settings were enabled merely because a workflow succeeded.
3. **Destination choice.** Prefer **source-repository native** or
   dedicated **ephemeral source-org self-hosted runners**, executed in
   a clean sandbox with no PR-controlled secrets and read-only
   checkout, when runner capacity is available. This avoids a
   cross-repository source export, but still needs independent runner
   security, required-check and full-main validation.
4. **If a private carrier is necessary**, separately approve its exact
   owner/name and immutable numeric repository ID, a reviewed
   source-free `main` base, workflow blob SHA, exact bounded test-plan
   blob/PR set, final carrier root-tree review/attestation and any
   mandatory carrier-branch controls. Review the carrier's real default
   branch and its actual protection/approval history. Make changes
   to the allowlist/contract only via CODEOWNER-reviewed protected-main
   governance. Existing `opto-sync/atto-ci` has **no standing grant**.
5. **Previous private test objects.** Inventory exact carriers/refs,
   checked-in payloads, artifacts, logs and retention conditions.
   Independently decide if restriction or GitHub Support cleanup is
   necessary. Do not equate ref deletion with object removal, and
   do not purge evidence before an authorized review.

## Administrator settings checklist (operator action, not automatic enforcement)

GitHub currently documents private-repository branch protection support on
GitHub Pro, Team and Enterprise plans; GitHub Free does not provide the
equivalent protected-branch controls for a private repo. An API response
telling the operator to upgrade is **not** proof of the organization's
billing entitlement. Confirm the actual repository/organization plan
with an authorized administrator first.

For an eligible repository, an administrator should open
`atto-lang/atto-source.java` → **Settings** → **Branches** → add or
edit the `main` branch protection rule (or the equivalent supported
repository ruleset). Configure, at minimum, the actual V3 policy's
stricter requirements, **not** merely one checkbox:

- Require pull requests and independent approving CODEOWNER reviews.
  Enable stale-approval dismissal after changes and prohibit
  self-approval. No unreviewed force-merge path.
- Require a **non-empty list of verified, actually executing CI status
  checks**, with the trusted status-check publisher/app selected.
  Do not require a check that has never run or report a zero-step failure
  as a pass. Add strict up-to-date integration-check expectations.
- Apply the protections to administrators; disable all review/status
  bypasses; disallow force pushes and branch deletion; require
  conversation resolution and the additional V3 controls.
- Verify that existing CODEOWNERS and the org's Ready-cycle four-party
  independent-review policy remain in force even if GitHub's built-in
  reviewer-count control cannot encode verified distinct AI identities.
- Independently read back the **live** `main` protection and all
  ruleset/review/status fields using an administrator-capable integration,
  and record the UTC verification, immutable branch SHA, source repository
  ID, protected/ruleset output and security-owner identity in #65.

Useful GitHub guidance:
- https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/managing-a-branch-protection-rule
- https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches

**Fail closed:** do not grant a carrier destination or ship private
source simply because the protection UI looks configured. The
privileged V3 publisher must independently authenticate and verify all
enforcement, immutable carrier contract, exact source PR and
destination identity immediately before each permitted transfer.

**Source-local CI capacity option:** if paid GitHub-hosted minutes are
unavailable, use a *reviewed, single-use ephemeral, isolated runner
attached to the source repository*. Run only read-only source-local
checkouts and executable compiler tests; no ambient tenant credentials,
cloud secrets, shared persistent worker disks, or privileged
untrusted-PR execution. Confirm the runner really executes test steps
and publish its result to source native checks. A source-local runner
does not require a cross-org source grant, but is not a branch-protection
or review substitute.

## Release CI after governance authorization

Use the current, authenticated source `main` commit and exact heads
of PRs #58, #53, #52 and #51. Build a deterministic merge in the intended
sequence against **the complete source repository** (not merely a
sparse dependency closure). Run the original M1/Mux constructor negative
checks, channel-read pre-HIR checks, bounded Result host checks, and
functional-pipeline JVM and GraalVM Native Image compiled guest suites,
plus the relevant top-level build/security gates. Confirm actual executed
steps and non-empty log evidence; zero-step Actions failures do not count.
Re-run on changed heads, and again after integrations if main or
dependencies move.

Then enforce the authoritative `atto-lang/.github/AGENTS.md`
**current Ready cycle** review quorum. In its first 72 hours the
named Codex/ChatGPT, Claude/Opus, authenticated ORESoftware and
authenticated the1mills slots require independent current-cycle reviews.
The PR author cannot self-approve: get an explicitly authorized
independent substitute for that named slot when necessary. Review
conflicts and competing PRs and preserve the source branch rules.
No merge is authorized by this decision record itself.

## Evidence checklist for closing #65

- [ ] Independent security-owner applicability determination recorded
- [ ] Source `main` protection verifiably enabled or an explicitly
      security-approved, non-export source-local CI option adopted
- [ ] If a carrier is used, immutable ID / allowlist / contract /
      default branch / reviewed workflow and test plan verified live
- [ ] Prior unauthorized-carrier objects/refs/logs reviewed for
      retention or incident handling
- [ ] Full imported-main integration test actually executed on
      current heads and verified artifact provenance
- [ ] Fresh, independently authenticated Ready-cycle reviews and
      author-substitute rule satisfied
- [ ] No conflicts, no bypassed branch rules, and a recorded
      release/merge decision

**As long as these boxes are incomplete, #65 stays open and the
affected merges/exports remain on hold.**
