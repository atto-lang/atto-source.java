# Cross-organization AttoLang policy guest admission (Java reference)

This source-local Java JUnit suite compiles and **executes** two immutable
`.atto` files through `LinkedProgramRunner`. It compares the *actual guest
stdout* against separately checked-in, blob-pinned owner expectations.

| Owner | Owner PR head (immutable source snapshot) | Reviewed source blob | Expected stdout blob |
| --- | --- | --- | --- |
| [ores-otel/ores.otel.log #124](https://github.com/ores-otel/ores.otel.log/pull/124) | `27d10b6e4b80e91fb785aa008b56404b1d04c044` | `0388755a1660ed3d9543e1a5db982a9c328b91bc` | `c4e6e607cedc1dd890981ff5f76e101f41d016f6` |
| [opto-sync/opto-sync-clients #164](https://github.com/opto-sync/opto-sync-clients/pull/164) | `284b2b94c3c62b6581e8e2ddf148b0e874a5e5fb` | `50f691b51c782835b26cb7d9cea3db44d7678d41` | `8a9e229a36d840df32d35fd54dc021ac7c9503af` |

The `.atto` bytes are unchanged from the owner PR source. No GitHub
credentials or cross-organization private-source checkout are required.

Run `mvn -B -ntp -Dtest=AttoFormalPolicyGuestTest -DfailIfNoTests=true test`.
The test uses the real linked compiler/guest entry point and rejects malformed
programs in two separate negative tests. Both original policies encode a
small integer predicate and deterministic stdout; they do **not** expose
new host capabilities, external networking, blocking IO, or production APIs.

## Evidence and limitations

- A passing GitHub job on the exact new Java PR head is **Java/Truffle
  guest-execution evidence** for these two reviewed source snapshots only.
- This does **not** establish that the owning OTel/Opto production
  implementations refine the AttoLang sources, or that the independent
  bounded abstract checker is a full temporal proof.
- The compiler parser negative controls are deliberately distinct from
  the owners' two model-level unsafe mutation controls.
- LLVM, .NET, Wasm, and Forge/Cranelift guest parity remain **NOT_EXECUTED**.
- Do not promote status on the owning projects without a fresh source blob
  reconciliation, exact-head JUnit/nonzero-step run, independent review,
  and real implementation trace replay.
