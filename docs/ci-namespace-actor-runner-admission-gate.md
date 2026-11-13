# Actor CI runner admission gate — source-free evidence

## Observed, not inferred

| Repository and PR | Workflow run / job | Observation |
| --- | --- | --- |
| Java #3034 (exact-head Truffle action nodes) | [43354424830](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43354424830) / 131652913294 | `full-source-head` concluded failure, jobs API reported **no steps** |
| Java #3030 (checked output ABI) | [43351781631](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/43351781631) / 131644321309 | failure before steps |
| Private selected-blob carrier voxletra #40 | [43351951516](https://github.com/voxletra/voxletra-e2e/actions/runs/43351951516) / 131644877480 | failure before steps |
| Source-free private alternate probe benefactor-cc #24 | [run 43354696205](https://github.com/benefactor-cc/benefactor-e2e/actions/runs/43354696205) / job 131653813347 | **PASS with three executed steps**, including an actual bash step; **does not** validate Atto source |

The GitHub connector exposes job conclusion, step summaries and job log retrieval, but does not expose organization Actions billing, budgets, runner-group assignment or workflow validation annotations. **Root cause is not established.** Zero-step failure does not prove Java compilation or test failure.

## Discriminating investigation (admin/maintainer)

1. Open each workflow run overview and check the job-level error/annotation before clicking `Re-run`. An invalid workflow file can fail before runner assignment.
2. Check organization Actions policy, private-repository permissions and any required workflow approval on the relevant branch/PR.
3. Check Actions billing/spend limit, included minutes, budget, account payment status and storage allocation in GitHub billing settings. Only org billing admins can confirm those; do not infer exhaustion from `steps=null`.
4. Check availability of the requested `ubuntu-24.04` runner and whether a GitHub-hosted runner is authorized for private PR jobs.
5. Execute the source-free probe in approved **private** benefactor-cc only. Accept a result **only if a concrete execution step and log output** are present; a completed failure without steps remains blocked.
6. If the alternate carrier is funded, mirror the **entire Java PR #3034 Git tree**, verify exact source commit and tree SHA and every file blob without using cross-organization tokens/secrets. If full-tree mirroring is unavailable, label selected-file JUnit runs as subset only.
7. Run JDK25/GraalVM full Maven suite with strict Surefire checks; capture zero skip/error/failure evidence from the exact branch and independent approval. JDK21 fallback tests are not optimized Graal25 or Native Image.

**Do not** remove runner checks, fetch private source via unaudited web endpoints, or mark this draft as merge-ready to bypass CI.

## Whole-tree mirror transfer blocker, independently checked

The latest Java #3034 source head `8432702ca33402fc355292d3ada1c69dd9cf7563` has Git tree `a9941a82e58956a7939922d27bd9c6e08a0a0e9f`. Git's recursive tree endpoint returned `truncated=false`, **2,174 tree entries / 1,955 blob files**, with roughly **15.9 MB** of blob contents. These SHA values establish the exact *source* tree to be tested, not any cross-organization mirror.

A token-free GitHub connector `create_tree` probe in `benefactor-cc/benefactor-e2e` failed with HTTP **422** (`tree.sha ... is not a valid blob`) when given a blob SHA from the independent private Java repo. The Git objects cannot simply be referenced across unrelated repositories. No carrier branch was modified by that unsuccessful object-creation probe. An entire-tree mirror therefore requires actually transferring the 1,955 files and independently verifying all paths, modes and blob hashes. It has **not** been performed. Selected-file copying is not an acceptable substitute for whole-tree CI.

Current verified split: **benefactor runner admission PASS; Java source-tree transfer NOT DONE; Java JDK25/JUnit/Native Image NOT EXECUTED**. Billing/permissions in source and voxletra remain unconfirmed; this evidence does not establish that those organizations are out of minutes.
