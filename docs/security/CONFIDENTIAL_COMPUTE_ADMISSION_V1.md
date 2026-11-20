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

## Test

`mvn -Dtest=TenantIsolateManagerTest test` (requires the repository toolchain).
Tests require rejection of both trusted and adversarial confidential
requests without side effects, while preserving explicitly trusted admission.
These are host API tests, **not** hardware attestation tests.
