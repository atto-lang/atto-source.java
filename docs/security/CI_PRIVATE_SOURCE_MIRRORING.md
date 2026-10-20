# Private CI mirrors: deny-by-default policy

Never transfer private Oreslang source into a public or internal GitHub
repository, including temporary refs, dangling Git objects, Actions artifacts,
cache entries, and logs.

## Destination approval

The policy file `.github/private-ci-approved-repositories.txt` is
**deny-by-default**. Active rows use
`owner/repo@numeric-github-repository-id`; repository names alone are not an
authorization boundary. The live GitHub repository ID must still match the
reviewed immutable ID immediately before export writes.

The preferred authorization mode is a reviewed policy on protected `main`.
When branch protection is technically unavailable, the only staged fallback is
`review-attested-v1`: each export needs two distinct, unedited GitHub issue
comments from the pinned maintainer and security owner. The fallback may be
considered only when GitHub explicitly reports that both classic branch
protection and repository rulesets are unavailable because of the current
private-repository plan; a generic 403/404, disabled setting, or integration
permission failure is not sufficient evidence and fails closed.

Both comments must be the exact same one-line payload binding the live source
PR/head/tree, destination name/ID, exact new `ci/oreslang-*` target branch,
reviewed destination carrier base commit/tree, and the authoritative-main blobs
for the policy, preflight, destination guard, wrapper, and publisher. Any bound
change voids the comments. The fallback is not active merely because its code
exists on a feature branch; it must itself be independently reviewed and merged
first.

Do **not** accept a destination allowlist or approval payload from arbitrary
environment variables or unreviewed workflow inputs. The source checkout may
be the exact PR tree being tested, but every security-critical export program
must hash identically to its reviewed `main` blob before the preflight passes.
A PR therefore cannot weaken its own exporter.

## Destination-bound source snapshot publisher

The only supported transfer entry point is **not an arbitrary command
launcher**. It accepts one reviewed destination, a bounded local source directory, a new
isolated CI branch, and the exact source PR/commit/tree authorization:

```bash
scripts/run-in-private-ci-destination.sh trusted-org/private-ci \
  --source-dir . \
  --branch ci/oreslang-verified-20261019 \
  --source-pr 1425 \
  --source-commit <40-hex-live-pr-head> \
  --source-tree <40-hex-exact-tree> \
  --maintainer-approval-comment <comment-id> \
  --security-approval-comment <comment-id>
```

The two comment flags are omitted only when protected-main authorization is
effective. Before reading potentially sensitive source bytes, the publisher
runs `preflight-private-ci-mirror.sh`. That preflight verifies the exact clean
source checkout, live same-repository PR head, fresh authenticated `main`,
authoritative security-program blobs, applicable approval mode, policy row, and
destination identity. It also resolves the reviewed destination carrier
directly by its immutable Git commit SHA and requires that commit's tree to
equal the reviewed carrier tree embedded in the authoritative exporter.
Movement of the destination's mutable default branch is not an authorization
input; changing the reviewed carrier commit/tree still requires a new reviewed
policy/exporter revision. The checked-in policy must explicitly list
`owner/repo@numeric-id`.

The publisher then checks the live GitHub repository ID, full name and
`visibility=private` / `private=true` before sending private bytes, and
rechecks the immutable ID and private visibility immediately before each
write. Every GitHub API write is constructed from
that **same validated owner/repo**, rather than a caller-provided URL, Git
remote, shell command or separate destination argument. It creates blobs,
then a **fresh standalone source tree with no `base_tree`**, followed by a
root tree based on the immutable reviewed carrier tree that replaces the
**entire** `private-oreslang-source/` directory. This removes stale private
files while preserving only the workflows/files present in that reviewed
carrier object. Finally, it creates a commit whose parent is the reviewed
carrier commit and the **new, approval-bound** `ci/oreslang-*` ref. Existing
branches, including `main`, are never updated. A later change to destination
`main` neither grants nor revokes approval for an already reviewed immutable
carrier.

## Reviewed destination execution containment

The reviewed carrier is immutable Git commit
`c5f5155259aa37b808f4e96dbe09cddded4c04ed`, tree
`15fcb558350a2965d9edf1f503d45a6d8e7f98df`. Authorization binds those Git
objects directly; movement of destination `main` is not an authorization
input. The preflight also resolves
`.github/workflows/private-oreslang-exact-tree.yml` from that immutable
carrier commit and requires reviewed blob
`ef7b0caaa1aedb56864f01fad14bfe88321f1ab1`; it never trusts a cleanup
workflow fetched from mutable destination `main`.

Every published carrier commit carries a second, machine-checked provenance line
binding the reviewed carrier base commit and tree. The workflow fetches exactly
one parent, requires that parent to equal the declared carrier base, verifies
the parent tree, reconstructs the permitted root as the reviewed carrier tree
plus the exact `private-oreslang-source` subtree, and requires that reconstructed
root to equal `HEAD^{tree}`. This prevents a matching source subtree on an
unreviewed carrier from producing acceptable evidence.

Carrier contract run `39348566012` also passed the source-free adversarial sandbox contract on the reviewed carrier commit, including masked mount roots, cleared token environment, network isolation, dropped capabilities, and host-write denial.

The carrier treats the mirrored source and every source-owned build/test
program as untrusted. For a real private source run it first requires the
mirrored `pom.xml` blob to equal the reviewed source-main POM and rejects an
unreviewed `.mvn/` control plane. Maven dependencies and the fixed Surefire /
JUnit Platform provider runtime are prefetched with network access under a
cleared environment before any source-owned code is compiled or executed.

All subsequent source-owned shell scripts, Maven compilation/tests, GraalVM
builds, native-image builds, and generated guest executables run in a
Bubblewrap namespace with outbound networking disabled, PID/UTS/IPC isolation,
a user namespace with nested user namespaces disabled, all capabilities
dropped, runner home/runtime locations masked, and a cleared environment. The
sandbox exposes only the exact source tree plus the isolated Maven repository
and private temporary storage as writable state. Maven is forced into offline
mode with an empty settings file.

Private-source command output is redirected to runner-local files and is not
streamed into Actions logs. Failures from real private source produce only a
fixed trusted error message. The only detailed-log exception is a source-free
canary bound to both a fixed fake source commit and the fixed synthetic canary
tree; that fixture also asserts that GitHub/ACTIONS credential variables are
absent, runner-private paths are hidden, and outbound TCP is unavailable.

Source-free provenance run `39328694706` validates the pinned carrier:
exact-tree and concurrency, JDK 25/GraalVM verification, Native Image
AOT/hybrid, and the approval-bound inline cleanup job all completed
successfully. Its commit declared the actual reviewed carrier parent/tree, and
all three source-executing jobs independently accepted that provenance before
compiler work. Cleanup deleted the exact tested ref, which was independently
verified absent afterward.

Negative source-free run `39328782255` used the same synthetic source tree
but deliberately declared the previous carrier base. All three source-executing
jobs rejected it at the provenance gate before compiler work; the cleanup job
still succeeded, and that test ref was also independently verified absent.
These runs are containment/harness evidence only, not evidence that private
Oreslang source passed compiler tests.

The publisher bounds the export to 1,024 files / 40 MiB total / 10 MiB
per file, requires the source directory to remain **inside the trusted
checkout**, and walks the source **through directory file descriptors** to
prevent parent-directory symlink swap races. For an exact repository-tree
export, pass the trusted checkout root as `--source-dir .`; the root
`.git` metadata entry is excluded because it is not part of `HEAD^{tree}`.
Nested `.git` paths remain rejected. The publisher also rejects symbolic
links, hardlinks to outside files, FIFOs, and other unsafe filesystem
objects. Directory enumeration is bounded to 4,096 entries,
1,024 directories and a maximum depth of 64. It verifies every uploaded
Git blob SHA-1 against the source bytes. It checks that the reviewed destination policy fingerprint and immutable
repository ID have not changed before every API write, in addition to
rechecking the destination's live private visibility. It does not print source contents in API failures.
API failure before ref creation can leave unreferenced Git objects in
the private destination. Reachable `ci/oreslang-*` refs are deleted by a
separate `contents: write` cleanup job inside the same immutable, reviewed
exact-tree workflow after all three source-executing jobs finish. The
source-executing jobs retain `contents: read`; the cleanup job does not check
out or execute source and deletes only when the live ref still equals the exact
tested `GITHUB_SHA`. There is no mutable default-branch `workflow_run`
cleanup control plane. Unreferenced Git object retention remains subject to
GitHub server-side garbage collection and is not immediate cryptographic
erasure.

It does **not** intercept arbitrary connector/API tool writes, cannot
completely prevent visibility changes between API checks, and cannot
guarantee that the source directory itself is free of secrets. Run only through the reviewed authorization path using narrowly scoped
credentials. There is no permission to use a public/internal repository, or to
treat available Actions minutes as source-export approval.

Test without network access or sending any source:

```bash
bash scripts/test-private-ci-destination.sh
bash scripts/test-preflight-private-ci-mirror.sh
python3 scripts/test-publish-private-ci-source.py
```

The Bash tests use synthetic Git/policy/API fixtures; Python tests mock all
GitHub API writes. They never transfer Oreslang source. A policy row on a draft
branch is **not authorization**: the applicable protected-main or independently
reviewed dual-attestation gate must also pass.

## Active disclosure incident

Private issue #832 tracks source-bearing commits in a public CI repository.
Resetting branch references did **not** remove the Git objects: commits were
still retrievable by SHA. Administrators must restrict public access and seek
GitHub Support assistance for retained Git objects, Actions caches/logs, and
other copies before resolving the incident.
