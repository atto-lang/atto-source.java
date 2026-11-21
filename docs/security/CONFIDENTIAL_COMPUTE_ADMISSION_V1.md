# Confidential computing admission v1 — Java/GraalVM

**Status: staged fail-closed boundary; no production confidential-computing support.**
This patch reserves `TenantIsolateManager.open_confidential_tenant_vm` and makes it
reject all requests. A JVM `Context`, Graal sandbox policy, Native Image AOT,
actor heap, or Graal isolate is **not** evidence of AMD SEV-SNP, Intel TDX,
Arm CCA, or any other verified hardware trusted execution environment (TEE).

## Threat boundaries

- **Host TEE boundary**: hardware-backed confidentiality/integrity of the
  whole protected host workload from the untrusted infrastructure, within
  the platform's documented threat model.
- **Atto TenantVM boundary**: isolation *between* mutually untrusted tenants
  sharing the same process. Host-wide TEE protection does not supply this;
  the existing `open_trusted_tenant_vm` remains explicitly trusted-only.
- **Actor boundary**: sealed state, resource ownership, mailbox permissions,
  no raw pointer or capability escape. A TEE does not remove these checks.

## Future independently reviewed provider admission gates

1. A trusted host creates a fresh unpredictable challenge and binds it to
   the exact workload image digest, expected measurements/TCB/security version,
   deployment identity and requested TEE technology. A guest-supplied claim,
   environment variable or `is_confidential` boolean is not evidence.
2. Verify signed hardware attestation using current vendor trust roots,
   certificate-chain and revocation policy, debug-mode prohibition, freshness,
   platform status, and an approved security baseline. On every error or
   unsupported platform **reject before constructing a context**.
3. Provision secret material only over an authenticated channel bound to the
   verified attested workload identity and ephemeral challenge/key. Never
   put keys in host logs, ambient environment variables or unsigned manifests.
4. Bind authorizations to a host-issued TenantVM identity and generation;
   retire attestation-linked secrets/keys on revocation, restart and shutdown.
   An attested *host* is not proof of per-TenantVM physical memory separation.
5. Maintain capability-checked socket/I/O broker, per-tenant quotas and
   independent teardown. Run adversarial compiled-guest tests plus TEE
   attestation/key-release negative tests on genuine supported hardware.
6. Explicitly report coverage: `HOST_ONLY`, `NOT_EXECUTED`,
   `HARDWARE_ATTESTED` only with verifiable execution evidence and exact
   workload/firmware/toolchain identities. A passing unit test cannot
   establish `HARDWARE_ATTESTED`.

No verifier or key-release provider is installed by this PR. No environment
flag can opt in to an unverified TEE. See `docs/TENANT_VM_NAMING_V1.md`
for the separate shared-process TenantVM security target.

## Portable preflight and scope

`ConfidentialAttestationPreflight` implements a one-shot, host-only
challenge/policy state machine with defensive byte-array copies. Even a
plausible fake quote can only reach `UNVERIFIED_CLAIMS_MATCH`; no key release
or privileged VM admission is possible. The runtime rejects confidential
requests independently of the preflight.

Shared draft sources: [spec #193](https://github.com/atto-lang/atto-spec/pull/193),
[conformance #543](https://github.com/atto-lang/atto-platform-conformance/pull/543),
[profiles #25](https://github.com/atto-lang/atto-target-profiles/pull/25).

## AMD SEV-SNP raw report and signature primitive

`SevSnpReportV1` inspects the 1,184-byte raw SNP firmware report:
64-byte `REPORT_DATA`, **48-byte SHA-384 launch measurement**, signed
672-byte prefix, VMPL, signer kind, debug policy, algorithm and per-component
TCB floor. It verifies the ECDSA P-384/SHA-384 report signature given an
**already authenticated** EC public key. The key is not authenticated by
this class: AMD pinned product roots, VCEK/VLEK certificates, vendor TCB
extensions, chip ID, revocation, and secure secret-release channel are not
implemented. A signature from a freshly generated synthetic key is **NOT**
hardware attestation.

`compute_bound_report_data` binds verifier nonce, deployment identity
and an ephemeral TLS public-key SPKI to a 64-byte SHA-512 transcript.
A wrong nonce, image identity or SPKI changes the binding. Successful
signature checking cannot bypass the unconditional confidential-TenantVM
admission denial.

New local host-only smoke: OpenJDK 21 compiled and exercised the raw-report
policy and signature code. Repo JUnit regression is staged as
`SevSnpReportV1Test` but remains **NOT_EXECUTED** until verified on its
exact current head.

## Offline VCEK endorsement validation (M2 staging)

`SevSnpVcekEndorsement` uses Java's X.509 PKIX path validator, SHA-256
ARK fingerprint matching, and required VCEK + ASK CRLs. It checks
Family-19h AMD VCEK structVersion, TCB component OIDs and the 64-byte
CHIP_ID, then verifies the signed quote with the certified VCEK key.
Missing issuer CRLs or a guest-provided fake root cannot create an
authentic vendor attestation. Supported scope is deliberately **VCEK
Family 19h only**; VLEK and Turin remain unsupported.

This is offline crypto staging, not deployment/product trust-root
provisioning, a live SNP quote request, or an attestation-bound TLS
secret broker. `TenantIsolateManager.open_confidential_tenant_vm`
still denies every request. Added regression
`SevSnpVcekEndorsementTest` has no exact-head JUnit execution claim.

## Test

`mvn -Dtest=TenantIsolateManagerTest test` (requires the repository toolchain).
Tests require rejection of both trusted and adversarial confidential
requests without side effects, while preserving explicitly trusted admission.
These are host API tests, **not** hardware attestation tests.
