# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is **empty by
default**. No cross-repository mirroring is authorized until maintainers and
security reviewers explicitly add an exact `owner/repo` destination through a
reviewed, protected-branch change.

Do **not** accept a CI destination allowlist from environment variables or
unreviewed workflow inputs: a caller must not be able to grant its own approval.
Run the checked transfer from trusted, reviewed source, not an untrusted PR
checkout. If an approval policy can be modified by the code under test, treat
the result as untrusted.

## Only supported shell transfer entry point

Once a private destination has been approved in the policy file, invoke a
reviewed transfer command **as argv**, never by `eval`:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci -- \
  your-reviewed-transfer-command --destination trusted-org/private-ci
```

The wrapper invokes a fresh authenticated GitHub API check using the
`github.com` host, and requires matching `full_name`, `visibility=private`,
and `private=true`. Public, internal, inaccessible, unexpected or unapproved
repositories fail *before* the transfer command executes. The checked-in
policy rejects symlinks, malformed entries, wildcards, and unexpected fields.

The wrapper protects **only shell transfer paths that invoke it**. It cannot
intercept connector/API calls, prevent all time-of-check/time-of-use changes,
or undo previously uploaded Git objects. The reviewed transfer itself must
revalidate its *actual* destination, use tightly scoped credentials, and limit
artifact/log retention. Do not move the transfer to a different repository to
bypass exhausted Actions minutes.

Test without contacting GitHub or transferring source:

```bash
bash scripts/test-private-ci-destination.sh
```

The test uses an isolated fixture policy in a temporary checkout; it never
modifies the real deny-by-default approval file.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
