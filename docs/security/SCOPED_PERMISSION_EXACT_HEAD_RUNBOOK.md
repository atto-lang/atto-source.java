# Java permission security: exact-head execution runbook

Status: **RUNNER EXECUTION PENDING**. This runbook is not a CI pass certificate.

## Chain under test

Stacked draft PRs:
- #2269: adversarial isolate capability ceiling
- #2277: actor-local immutable restriction chains and native-I/O authority
- #2286: wildcard/IPv4/IPv6 network validation and exact resource identity
- follow-on listener-port-zero PR: outbound network authorization denies port 0; explicit HTTP listener authorization can request OS-assigned port 0

The checked-in 44-case `contracts/v1/scoped-permissions-v1.json` oracle is unchanged. Its
`endpoint-invalid-port` case MUST remain `deny`. Never rewrite the pinned v1
vector digest to justify accepting outbound port 0.

## Admissible execution environments

1. Source-org job with a genuinely assigned GitHub runner and recorded steps, or
   a vetted **source-local** operator runner with a clean, exact Git checkout.
2. A cross-org destination ONLY after the independently reviewed V3 private
   source transfer gate authorizes its exact numeric repo ID and carrier
   workflow. No raw connector-based mirror, temporary-ref exception, or
   token-free bypass is authorized by the present empty allowlist.
3. A source-free canary on an alternate org tests only availability, never
   compilation of this private tree.

## JDK 25 / GraalVM exact-head focused acceptance

Run from a CLEAN checkout in the private source repository on an authorized
runner. Set `EXPECTED_HEAD` to the 40-character immutable commit SHA of the
open PR just before execution, and compare it with the current PR head through
GitHub independently.

```bash
set -euo pipefail
: "${EXPECTED_HEAD:?must pin the exact current PR head}"
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
test -z "$(git status --porcelain)"
git rev-parse HEAD^{tree}
java -version        # must report GraalVM JDK 25
mvn -version
mvn -B -ntp -Dores.runtime.carriers=native \
  -Dtest=AdversarialPermissionCeilingTest,ActorScopedPermissionDelegationTest,RuntimePermissionsTest,RuntimePermissionsNetworkAuditTest,RuntimePermissionsIdentityHardeningTest,RuntimePermissionsPortZeroContractTest,ScopedPermissionInteropTest \
  test
mvn -B -ntp -Dores.runtime.carriers=native verify
mvn -B -ntp -Pnative-aot -DskipTests package
mvn -B -ntp -Pnative-hybrid -DskipTests package
```

Native Image package success is **build evidence only**, not proof that
actual Oreslang guest binaries executed native host-I/O allow/deny fixtures.
Record separately the **executed** program/output evidence for denied
filesystem reads/writes, environment reads, network connect/listen, wildcard
denials, worker-thread authority, child actor delegation and untrusted
isolate escape attempts. Native Image and strict-isolate execution must be
tested with the real compiler/runtime, not synthetic outputs.

Required receipt fields: exact source SHA and root-tree SHA, runner
identity, job/run URL, each shell step and exit status, test report totals
and failures, native executable SHA-256, exact guest inputs/expected outputs
and reviewer attestation. A job with `steps=[]`, `runner_id=0`, or an
unverified source SHA/tree has **NOT_EXECUTED** status regardless of GitHub's
final red/green badge.

## Administrator unblockers

- Source `main` is currently reported as `protected:false`.
  Set independent review, protected branch, non-empty required latest-head
  checks and no bypass. Recheck the effective live configuration.
- Check source-org Actions billing/minutes and runner eligibility for the
  actual failed run IDs; do not infer the cause from zero steps alone.
- Issue #962 tracks cross-org private-source export approval, including the
  reviewed destination allowlist and carrier contract. It remains a hard gate.

Do not merge any stacked security PR while these execution and governance
conditions remain unresolved.
