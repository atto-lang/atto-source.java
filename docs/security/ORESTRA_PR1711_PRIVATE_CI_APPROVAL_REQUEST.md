# Orestra PR #1711: private GraalVM CI approval request (review only)

**Status: NO source export is authorized by this document.** The source
repository's `.github/private-ci-approved-repositories.txt` still contains
zero active destinations, and live origin `main` reported `protected=false`
when checked on 2026-10-20. The proposed carrier
`orestra/orestra-conformance` is private and identity-pinned, but its live
`main` also reports `protected=false`. Do not create a private-source
carrier or execute the exporter until the policy plus **both** origin and
carrier protections have been reviewed and independently verified by the
trusted V3 publisher.

## Approval sequence

1. Source repo admins configure `main` protection: CODEOWNER approval,
   dismiss stale reviews, require >=1 approving review, enforce for admins,
   prevent force push and deletion, and deny review bypass. Verify live branch
   protection API/rulesets; `CODEOWNERS` alone is not sufficient.
2. Carrier repo admins independently protect the exact destination default
   branch with equivalent review/no-bypass/no-force-push/no-delete controls.
   A private repository with spare Actions minutes is **not** an eligible
   carrier while its trusted base/workflow can be rewritten without review.
3. Maintainers and security owners review an **exact** private carrier repo,
   the pinned, source-free default-branch base commit and workflow blob, and
   the reviewed test-plan Git blob. Then they authorize the destination
   through an approved protected-branch change to both
   `.github/private-ci-approved-repositories.txt` and
   `.github/private-ci-carrier-contracts.json`. No CI input/PR branch may
   authorize its own export.
4. Revalidate the live carrier identity, private visibility, protected default
   branch, workflow blob, test-plan blob and source PR head. Reject a shifted
   or unprotected carrier until contract approval is refreshed.
5. Transfer the **complete** original private Git tree only through the trusted
   V3 exporter under the protected source policy. Preserve executable bits,
   reject symlinks/submodules and verify the mirrored subtree has exactly
   the original source root tree SHA. Never rely on a blob list from a partial
   source checkout or an unmanaged cross-org token.
6. Use only the carrier's trusted workflow/immutable test plan. The source
   code under test may not modify the workflow or execute a caller-supplied
   command plan. Keep any source-bearing mirror private with explicit
   retention/cleanup. Native evidence requires executed JDK25/Maven steps,
   JUnit results and exact source tree hashes, not green verifier-only tests.
7. Treat source execution itself as a confidentiality boundary. The source
   process must receive no repository write token, checkout credential,
   cloud credential or unrelated secret. If outbound network remains enabled,
   security review must explicitly accept that source-controlled build/test
   code can transmit private source bytes; otherwise use an egress-controlled
   runner while retaining loopback for the HTTP integration test.

## Candidate metadata (for review, not an approval)

- Source: `ores-truffle-oreslang/oreslang-source.java`
- Source PR: `#1711`, head
  `9eb092fbaa8939e8d21629e2dd8d2986c3de8966`
- Original source root tree:
  `f603f6bb4e2ebcac05fb3f20b83df9328386d8df`
- Proposed destination: private `orestra/orestra-conformance`
- Destination repository ID: `1426277234`
- Live destination `main` protection at audit time: `protected=false`
- Reviewed source-free carrier `main` after merging Conformance #13:
  `45a0b599e2dc45a57b5fed290eb03210cc8bafe0`
- Candidate workflow:
  `.github/workflows/oreslang-exact-tree-carrier.yml`
- Candidate workflow blob SHA:
  `f4759633e2d7bb0c528394325e770013a8cc1826`
- Trusted test plan:
  `ci/test-plans/java-pr1711.json`, merged by
  `orestra/orestra-conformance` PR #13
- Test-plan blob SHA:
  `1b00f6c863b87e262b9f56990b6f22ae5d6fbecd`
- Test command (JDK25/GraalVM):
  `mvn -B -ntp -Dores.runtime.carriers=native -Dtest=OrestraBootstrapNativeHttpTest -DfailIfNoTests=true test`

These values are **not a live authorization**. The source-free plan is now
merged and pinned at the reviewed carrier base above, but the carrier `main`
and source `main` both remain unprotected. Source authorization is staged in
draft PR #1798 and must not merge until both protection gates are live and
CODEOWNER/security review is enforced. If the carrier advances, re-review and
re-pin before authorization.

## Native acceptance and rejection

- Real Oreslang source compiles and runs, actors reach READY, loopback HTTP
  answers the exact 16 expected requests, server and actors terminate cleanly.
- `/readyz` stays 503 because storage/quorum/agents are not operational.
- Report the actual Maven/JUnit guest failure, if any, with the exact source
  commit/tree and trusted carrier receipt, rather than calling provisioning
  failure a compiler failure.
- A successful source-free carrier verifier or Orestra Python model test is
  **not** native GraalVM evidence.

This file deliberately does not modify allowlists, protected carrier contracts,
GitHub branch protection, or CI authorization refs.
