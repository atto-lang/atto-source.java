# REVIEW-ONLY: private-CI post-merge provenance repair

This document is **non-authorizing**. It does not approve any private-source
destination, does not modify the destination allowlist, does not authorize a
source transfer, and does not treat historical unreviewed merges as trusted
provenance.

## Current protected-origin candidate

Observed source `main` commit:
`9f4f4a3b75dd4b124e8509b65a88a98213c11ab5`

Observed source `main` branch state at preparation time:
- GitHub reports `protected=false`;
- required-status-check enforcement is off;
- therefore this commit is **not** yet acceptable private-export authority.

Exact current security-control blobs:

| Path | Git blob SHA |
| --- | --- |
| `.github/CODEOWNERS` | `078909add1bb1d37eefc22c11dfa8e7ceb76aace` |
| `.github/workflows/private-ci-mirror-preflight.yml` | `288da01fdc0414cf9fbd3f47be459e7569927771` |
| `scripts/test-private-ci-codeowners-contract.sh` | `18e531ba5a85f8276800ece0914bfa554c319bf3` |
| `scripts/publish-private-ci-source.py` | `532451d1c131ed6bb90558f9491c089ed1801ce1` |
| `scripts/publish-private-ci-source-v3.py` | `b813da3cff66277bce13ea9e231ef210cea1c4e0` |
| `scripts/check-private-ci-destination.sh` | `ea000956404520ee2df938716b5032bb52368db1` |
| `.github/private-ci-approved-repositories.txt` | `aea82279b2a1b8e2e7a8d20fd68f975046b2c4ce` |

The active destination ledger contains no uncommented destination rows.

A machine-readable, non-authorizing snapshot is checked in at
`docs/security/private-ci-post-merge-provenance-repair-20261027.json`.
`scripts/verify-private-ci-post-merge-provenance-repair.py` rejects duplicate
manifest keys, repository/main identity drift, unsafe/symlinked paths, any
control blob mismatch, any manifest widening, and any active destination row.
A passing snapshot check proves only the reviewed governance bytes listed above;
it does **not** prove live branch protection, incident closure, destination
authorization, or permission to transfer private source.

## Provenance defect being repaired

The following security/control PRs were merged with **zero recorded pull-request
reviews**:
- #1028
- #1743
- #2072
- review-only #2062
- #2090 (the CODEOWNERS/preflight terminal-rule work)

#2071 was closed without merge. Its terminal CODEOWNERS/preflight/test content
is nevertheless present on current `main`, through later `rebase #2090`
commits. The resulting blobs may be reviewed as code, but their historical
landing path must not be used as evidence of independent approval.

This repair PR exists so an independent reviewer can attest the exact current
control bytes without retroactively converting the prior unprotected/unreviewed
merges into trusted authority.

## Required before private-source export

1. Obtain independent `@ORESoftware` approval on this exact PR head.
2. An administrator must enable source-`main` enforcement required by #1636:
   PR-only changes, CODEOWNER review, stale-approval dismissal, at least one
   approval, admins subject to the rule, no force-push/deletion, and no bypass
   for the private-CI security files.
3. Re-read live protection/ruleset state after the administrator change.
4. Re-read all exact security-control blobs from the then-current protected
   `main`; any drift requires a new review.
5. Only then create a **separate** reviewed destination-activation PR. This
   repair PR itself must never add an allowlist row.
6. Blocking private-source incidents and publisher release gates remain
   independently fail-closed.

## Java #2033 context

Current restacked candidate:
- PR #2033 head:
  `043495bc9243c74854677f8bcc193830d9340cdd`
- root tree:
  `3a64d6a7ee16cddc22a40e626995e87c4c77c31f`
- carrier #25 current head:
  `25444ad772cf589263cbf28f1adc0beac2e1d2fe`
- carrier plan blob:
  `99719a8e764705829601fc53ad64c6e9f268a8cf`

The seven carrier plan source-blob pins match the restacked #2033 head exactly.
This fact is validation context only; no source-bearing carrier execution is
authorized by this document.


## Refresh note

The source `main` authority candidate advanced after this repair PR was opened.
The snapshot was re-read and repinned to
`9f4f4a3b75dd4b124e8509b65a88a98213c11ab5`. All seven governed control blobs are unchanged from the prior
snapshot, and the approved-destination ledger still has zero active rows.
This refresh does not change the non-authorizing status of this PR.


### 2026-10-28 refresh

Source `main` advanced to `9f4f4a3b75dd4b124e8509b65a88a98213c11ab5`. All seven governed private-CI
control blobs were re-read at that exact commit and remain byte-identical to
the prior snapshot; the active destination ledger still has zero uncommented
rows. This refresh remains review-only and non-authorizing.

### Current-main review refresh (2026-10-29)

Re-read the seven governed control blobs against Java `main` at `9f4f4a3b75dd4b124e8509b65a88a98213c11ab5` before this review-only refresh; all seven blob identities are unchanged, and `.github/private-ci-approved-repositories.txt` still has zero uncommented destination entries. This remains an observed, **unprotected** `main` snapshot, not protected-origin authority or a private-source transfer authorization. Prior Java/LLVM/security review incidents and protected-main checks remain open. A future main or control-blob change requires fresh readback and independent review.
