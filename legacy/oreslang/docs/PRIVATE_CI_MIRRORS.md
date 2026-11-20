# Private-source CI mirror admission

Never mirror this repository's private source into public, internal, or unknown-visibility GitHub repositories. Even unreferenced Git blobs or trees can remain retrievable by SHA. Deleting/resetting branch refs does not prove deletion of Actions logs, artifacts, caches, PR refs, forks or dangling objects. Historical exposures require a confidential security incident response and GitHub Support purge review.

## Required fail-closed preflight

Before any Git blob/tree creation, branch creation containing source, commit, artifact upload, log emission or workflow dispatch in a cross-repository CI carrier:

1. Authenticate and verify BOTH source and destination GitHub repositories with their pinned numeric repository IDs (not names alone). Both must report private=true AND visibility=private, be distinct, and not be archived or disabled.
2. Supply least-privilege read credentials through process environment variables ORES_SOURCE_READ_TOKEN and ORES_DEST_READ_TOKEN. Do not echo credentials, place them in CLI arguments or commit them.
3. Invoke the gate before any source bytes are transferred:

       python3 scripts/ci_private_mirror_preflight.py \
         --source ores-truffle-oreslang/oreslang-source.java \
         --source-id "$PINNED_SOURCE_REPOSITORY_ID" \
         --destination "$AUTHORIZED_PRIVATE_CARRIER_FULL_NAME" \
         --destination-id "$PINNED_DESTINATION_REPOSITORY_ID"

4. Treat missing credentials, 404/403, network failures, redirects, public/internal/unknown visibility, mismatched identities or malformed metadata as hard failures. NO source-transfer operations are permitted after denial.
5. Recheck before each source-transfer batch and immediately after. This preflight is not atomic with Git writes; enforce organizational controls against visibility changes during transfer and use a dedicated private carrier.
6. Verify the full resulting Git tree, not only selected blobs. An Actions failure with zero executed job steps is not evidence of running tests.
7. Never put private code in a public benchmark or documentation dashboard; use non-sensitive provenance and aggregate results only.

Offline unit tests:

    python3 -m unittest discover -s tests -p 'test_ci_private_mirror_preflight.py' -v

The authenticated two-repository gate rejects not only public or archived repositories,
but also **missing** `archived` / `disabled` metadata, non-boolean status
values, and malformed pinned ID inputs (including Python booleans). Valid
metadata must affirmatively report `archived=false` and `disabled=false`
for both the source and destination.

Both read credentials are validated before any repository metadata fetch.
Bearer headers accept only bounded ASCII token68 syntax; whitespace, control
characters, injected CR/LF, and malformed or oversized credentials fail closed,
with no token value reflected in diagnostic errors.

**Integration limitation:** `scripts/ci_private_mirror_preflight.py` is a
standalone verifier; its presence alone does not prove the actual snapshot
publisher invokes it. Until issue #962's independently reviewed publisher
provenance and both-repository admission are enforced, the protected source
destination allowlist must remain unapproved and private-source publishing
must not be treated as authorized.

The gate intentionally has no skip/public override or offline metadata admission mode. GitHub connector-based and other direct API mirroring tools MUST perform equivalent authenticated private/private checks before writes; this utility cannot intercept their calls.

This is a prevention control, not historical object erasure and not approval to merge release PRs.
