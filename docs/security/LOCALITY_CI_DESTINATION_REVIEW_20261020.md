# Review request: exact-tree locality CI carrier (2026-10-20)

**REVIEW ONLY — no private-source export authorization in this branch.**
The destination allowlist stays empty, and no contract is broadened by
this change. This review describes prerequisites for Java PR #1500.

## Candidate and test scope

- Proposed private destination: `fiducia-cloud-test/java-client-consumer`
- Live GitHub destination repository ID observed: `1323679401`
- Carrier review PR: https://github.com/fiducia-cloud-test/java-client-consumer/pull/30
- Reviewed candidate plan path: `ci/test-plans/java-pr1500.json`
- Exactly permitted source: `ores-truffle-oreslang/oreslang-source.java` PR #1500
- Proposed tests: source-exact JDK21 host-topology smoke, CpuTopologyTest,
  ExecutionPlacementTest, and subsequent Maven full-suite/native-carrier gates.
- All source-bearing refs must be private, temporary, and receipt-bound.

## Hard blockers — fail closed

1. As inspected on October 20, origin `main` reported
   `protected: false`. An administrator must enable real branch protection:
   require CODEOWNER approval, stale-review dismissal, enforcing admins,
   no force pushes or deletion, and no PR-review bypass. Check live branch
   protection, not only a CODEOWNERS file.
2. Carrier PR #30 must be reviewed and merged into its source-free `main`
   by its owners. Verify its private visibility and pin its **final** `main`
   commit, exact carrier workflow blob SHA and exact immutable test-plan blob.
   A mutable review branch is NOT an approved execution base.
3. Maintainers and security owners must review a **separate protected-main**
   update to BOTH `.github/private-ci-approved-repositories.txt` and
   `.github/private-ci-carrier-contracts.json`. The former must list one
   exact repo slug; the latter must bind live repo ID, exact base SHA,
   workflow blob, and test-plan SHA with allowed PR #1500.
4. The dispatcher/publisher must execute from verified official protected
   `main` under narrow permissions, attest current PR head/tree and live
   destination, and publish only an exact-tree source subtree plus digest-
   bound expiring receipt with a dispatcher authorization ref. Never run
   source-bearing code from a PR-controlled authorization policy.
5. Carrier verifies every source blob/mode and root Git tree SHA against the
   original head, and refuses builds on drift, altered CI controls,
   unauthorized test plans or failed provenance. No cross-org private
   checkout, secret copying or public source objects.

## Release evidence

Source PR #1500 head as pinned at test dispatch, candidate carrier commit,
source tree, test-plan digest, successful stepful JDK/Maven runs, and logs
must all be recorded. Synthetic NUMA oracle tests do not prove actor
single-flight, actual page placement, or L1/L2/LLC performance.

**Until all the above pass, leave the destination unapproved and do not
transfer source.** This proposal is not an exception to the denial policy.
