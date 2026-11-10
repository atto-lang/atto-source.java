# Private Oreslang async-interop carrier candidate — evidence, NOT authorization

Status: **DRAFT / SECURITY REVIEW REQUIRED / NOT APPROVED**.

This document is an evidence handoff. It does **not** authorize cross-organization transfer of any private Oreslang Git blob, file, subtree, package, artifact, or log. The independently enforced private-source security policy takes precedence over any demonstrations here.

## Immutable repository identities

| Role | GitHub repository | Repository ID | Visibility | Main protected at audit |
|---|---|---:|---|---|
| private Oreslang source | `ores-truffle-oreslang/oreslang-source.java` | `1400792274` | private | **NO** |
| candidate private alternate carrier | `honeypot-r-us-test/hnpt-e2e` | `1346492776` | private | **NO** |
| public synthetic fixture source | `ORESoftware/ores-middleware` | not a private source authority | public | not relevant |

Check repo **numeric IDs** and visibility at execution time. Human-readable names alone are insufficient because a repository can be renamed, deleted, recreated, or made public.

## Successful execution evidence (public synthetic content ONLY)

1. [Private carrier no-checkout run #40611066991](https://github.com/honeypot-r-us-test/hnpt-e2e/actions/runs/40611066991) — source-free runner capability probe; **3/3 job steps passed** at head `3cb8efb626483a647b80bc8f23626f2d612cc436`. The temporary branch initially held exactly one source-free workflow file.
2. [Private carrier synthetic public Git-tree run #40611247040](https://github.com/honeypot-r-us-test/hnpt-e2e/actions/runs/40611247040) — **5/5 job steps passed** at head `abb59d9a527e997818ea34a8ff8e7ec36a1fd7d3`. The destination checked out **only a public three-file synthetic tree** and verified root SHA, blob SHAs, modes and exact path set.
3. Independently constructed public-source and private-destination root trees matched at **`62aa7c48387ddcce92127b6cbaa658b8c8e34ba5`**. Their exact three synthetic Git blobs were:
   - README: `7cf1e48a7aa6597a4ae850fce250d6a1129435a8`
   - JSON contract: `99e9c41fe021ac59a3f353e599435f9c34d4c184`
   - temporary Actions workflow: `b8576983c88d470b01e8edc8413ba884d0d3869f`.
4. These runs prove **successful private-repository GitHub Actions execution and public synthetic cross-org Git-object parity**, not Oreslang Java compilation, JDK25, GraalVM AOT, sustained Actions billing capacity, isolation, or safe private source export.

The candidate temporary branch is `audit/oreslang-source-free-runner-proof-20261027`, and no Oreslang private branch content was transferred to it.

## Mandatory independent security gates, ALL required BEFORE source export

1. **Administrator** enforces source-`main` protection (independent `@ORESoftware` CODEOWNER, required approvals and checks, no bypass) and protects any approved destination refs/workflows. Both source and candidate destination `main` were `protected=false` at this audit.
2. Source export authority independently reviews and approves **this specific numeric repository ID `1346492776`**, destination organization, visibility, repo isolation, workflow bytes, and access list. Formal approval must be recorded and effective under the source repository's current protected security contract. An assistant or a previous user's generic `go for it` does not substitute for independent reviewer authorization.
3. Authorized maintainer updates the reviewed protected source allowlist **only through the separate enforced policy review gate**. At this audit `.github/private-ci-approved-repositories.txt` contained **zero** active destinations. Merely editing an unprotected branch or another PR does not authorize export.
4. Before any transfer, confirm this specific PRIVATE destination can execute Actions **at that time**, has usable budget, least-privilege default `GITHUB_TOKEN`, no fork or PR-triggered execution of untrusted workflow definitions, restricted runner/environment, and controls preventing source disclosure through logs, job summaries, cache, artifacts, generated pages, dependencies, network or retention.
5. A dedicated read-only source Git tree snapshot must be pinned by exact approved commit SHA; verify **every blob content SHA, file mode, symlink target and recursive tree SHA** after copying to an authorized private destination. Do not read source via the destination's cross-org token, inject a PAT or publish private bytes in plaintext issue comments.
6. Independently inspect the exact transferred workflow bytes, restrict checkout to local mirrored branch and execution environment, prohibit automatic PR-controlled or public runner execution, assert no inherited secrets; verify GitHub Actions job **actually executed steps**. Fail closed on pending, zero-step failure, skip, test-count mismatch or tampered source tree.
7. Collect exact-head focused **42 JUnit** method receipts, full Java Maven test evidence, GraalVM Native Image AOT/hybrid execution, and independently observed physical Task retirement receipts. Keep [Java draft PR #2153](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2153) and [62-vector conformance draft PR #192](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/192) pending until executable evidence exists.
8. Cancel historical unsafe source-org self-hosted queue entries **40546884149, 40546920891, 40547417920**, and independently audit any more. Do not register runners with retired label `oreslang-isolated-ephemeral`. The separate source-free canary [PR #2172](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2172) is still independently reviewed before default-branch promotion.

## Explicit non-events

- **No Oreslang private Git blobs, trees or Java source files moved across organizations.**
- **No source-allowlist modification, branch protection, production runner registration, code merge, or workflow dispatch in the source repo.**
- **No Java JUnit/GraalVM Native Image tests run on the candidate carrier.**
- **This document does not make its own PR author an independent approver.**

Evidence issues: [source-org runner admission #963](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/963).
