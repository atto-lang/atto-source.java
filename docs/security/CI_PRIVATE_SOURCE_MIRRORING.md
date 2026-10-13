# Private CI mirrors: security preflight

Private Oreslang source must **never** be copied to a public CI repository, including temporary branches, Git blobs, workflow caches or artifacts. Git ref resets alone do not remove previously uploaded source objects.

Before any scripted cross-repository transfer:

1. Obtain explicit approval for the **exact** `owner/repo` destination. Record it in `ORESLANG_APPROVED_PRIVATE_CI_REPOS`, as a comma-separated list of full repository names. Do not approve entire organizations or inferred names.
2. Authenticate GitHub CLI with read permission for that destination and execute:
   ```sh
   ORESLANG_APPROVED_PRIVATE_CI_REPOS='trusted-org/private-ci' \
     scripts/check-private-ci-destination.sh trusted-org/private-ci
   ```
3. Proceed only after the preflight returns success. If the GitHub visibility API is unavailable, or the repository is `public` or `internal`, **stop**. Do not retry by copying source to another unapproved repository.
4. Minimize retained source, permissions, logs and artifacts. Clean up CI refs when validation ends; separately assess whether unreachable objects or caches remain retrievable.

This preflight is a **necessary but not sufficient** safeguard for scripts that invoke it. It does not intercept arbitrary GitHub API tool calls or retroactively purge leaks. Source mirrors must use a private runner under an approved trust boundary.

Test locally with `bash scripts/test-private-ci-destination.sh`. Track source-exposure incident remediation in private issue #832.
