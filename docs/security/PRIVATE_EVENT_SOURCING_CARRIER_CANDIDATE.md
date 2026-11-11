# Review-only private CI carrier candidate: event-sourcing JDBC/SQLite

**State: DENIED / PROPOSAL ONLY. This file grants zero export permission.**

## Candidate

- Destination: `atto-lang/atto-compilers-parity-testing`
- Immutable GitHub destination repository ID: `1448719198`
- Destination visibility: `private`
- Last examined destination main SHA: `d922becba613bdb48caf5d15d3dc5bce6baa6ea7`
- Source-free canary workflow on draft branch:
  `.github/workflows/atto-event-sourcing-source-free-canary.yml`
- Pinned workflow blob SHA on that branch:
  `e007b2ae7b8e46a83c42a5412025f2c683ddf40d`
- Latest confirmed successful source-free runner execution:
  [Actions run #42940311766, attempt 2](https://github.com/atto-lang/atto-compilers-parity-testing/actions/runs/42940311766)
  (**7 executed steps; success**). This proves only that an isolated
  no-checkout Node 22 SQLite canary could execute. It does **not** verify
  the cost budget or future availability of hosted runners.

## Intended exact source inputs (read-only until approved)

- Java host persistence:
  `ores-truffle-oreslang/oreslang-source.java` draft PR #2757.
  Proposed exact SHA when written:
  `539b8c0d222314b0b071be57926c44971ced7ac5`.
  It is a fast-moving draft; re-read the head and tree after any approval.
- Node host SQLite reference:
  `ores-truffle-oreslang/oreslang-platform-conformance` draft PR #668.
  Proposed exact SHA when written:
  `fb0ca9ec2c3f0b31620ffdc251d960adb06656cb`.
  Also refresh at actual handoff.

Only a **source-owned, approved** publisher may enumerate the source tree,
copy each allowed blob by exact SHA, verify immutable tree identity, and
create a temporary destination branch. The destination's GITHUB_TOKEN may
build its *local* mirror; it may never checkout private source from a
different organization. Do not copy secrets, credentials or GitHub App
tokens; a source-tree export is still confidential code transfer even
without secrets.

## Review and execution gates (all mandatory)

1. Independently approve the **exact repository ID, immutable carrier
   workflow blob, test-plan contract, origin commit, destination base commit
   and permitted source-tree scope**. The author's request alone is not
   a substitute for the required independent `@ORESoftware` sign-off.
2. Restore **enforced source-main protection** and required review checks.
   The latest live inspection reports source `main.protected=false`.
3. Resolve the live source-exposure/CI security issues and obtain an
   independently audited signed/tamper-checked provenance receipt.
4. Install and review a narrow destination-specific contract on the
   source-protected governance path; do not uncomment allowlist entries
   merely to make a workflow start. The protected main allowlist must
   become active only through its own independently reviewed change.
5. Pin the authorized destination workflow/tree and verify its selected
   runner, all Git blobs, symlinks/modes, SHA and branch leases. No
   unreviewed workflow injection; no untrusted PR payload in executable
   carrier control-plane paths.
6. Execute strict JDK 21 `javac -Xlint:all -Werror`, JDBC integration,
   actor runtime integration, Node 22 SQLite transactional/replay suites
   and SIGKILL crash fixtures. Record exact source tree SHA, job/run IDs,
   test counts, JDK/Node versions, outcomes and full exit codes.
7. Immediately revoke temporary source execution authority and delete
   ephemeral destination branches/refs after test artifacts/provenance
   have been validated. Verify deletion. Never merge a temporary mirror.

## Current hard stops

**No destination repository is active** in
`.github/private-ci-approved-repositories.txt` at the
security PR head; source main is unprotected, `@ORESoftware` independent
PR review was not present at the latest check, and the candidate
destination main also reported `protected=false`.
Do not publish any private source to this or another organization.
Source-owned Actions continue to fail at job admission before tests
execute. All implementation PRs remain DRAFT.

This proposal is intentionally informational; it must not be treated
as an authorization receipt, test pass or relaxation of the source
security policy.
