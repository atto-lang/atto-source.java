# Private CI mirrors: security preflight

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Only supported shell entry point

The checked destination MUST be in an explicit exact-repository allowlist. Use
the source-mirroring command as an argv vector (never eval or interpolate it
into a shell command string):

```bash
ORESLANG_APPROVED_PRIVATE_CI_REPOS='trusted-org/private-ci' \
  scripts/run-in-private-ci-destination.sh trusted-org/private-ci -- \
  your-reviewed-transfer-command --destination trusted-org/private-ci
```

The wrapper runs a fresh authenticated `gh api` check against `github.com`
and requires a matching `full_name`, `visibility=private`, and
`private=true`. A public, internal, inaccessible or different repository
causes a hard failure *before* the transfer command can execute.

The wrapper controls **only shell transfer paths that invoke it**. It cannot
intercept arbitrary connector/API writes, cannot eliminate time-of-check to
time-of-use changes after validation, and cannot undo public commits. The
transfer command must still revalidate its actual destination, limit the token
scope, and implement short-lived retention. Any destination change requires a
new allowlist approval and new visibility preflight. Never use this as a
workaround for exhausted Actions budget.

Run the local regression tests with
`bash scripts/test-private-ci-destination.sh`.

## Open security incident

See private issue #832. Source-bearing objects were still publicly retrievable
by commit SHA after ref reset. GitHub repo administrators must address
visibility, and GitHub Support must help assess removal of dangling objects,
workflow logs, caches, and other retained copies before incident closure.
