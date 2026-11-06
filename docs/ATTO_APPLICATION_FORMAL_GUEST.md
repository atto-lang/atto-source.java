# Source-linked AttoLang application formal guest tests (Java)

This contract adds **real Java/Graal linked guest execution** to the existing
*abstract/model-only* AttoLang policy references owned by OTel and Opto-Sync.
The source copies are byte-identical to reviewed public PR Git blobs, enforced
at runtime by `AttoFormalPolicyGuestTest.gitBlob`:

| Owner | Immutable source | Git blob |
| --- | --- | --- |
| OTel | [ores.otel.log #124 @ 27d10b6](https://github.com/ores-otel/ores.otel.log/blob/27d10b6e4b80e91fb785aa008b56404b1d04c044/formal/attolang/delivery_policy.atto) | `0388755a1660ed3d9543e1a5db982a9c328b91bc` |
| Opto-Sync | [opto-sync-clients #164 @ 284b2b9](https://github.com/opto-sync/opto-sync-clients/blob/284b2b94c3c62b6581e8e2ddf148b0e874a5e5fb/formal/attolang/ack_correlation.atto) | `50f691b51c782835b26cb7d9cea3db44d7678d41` |

The compiler must link each independent `.atto` program, execute `main`
and produce exactly the reviewed output. Negative semantic vectors demand
OTel conservation default-deny and Opto-Sync stale/future/uncommitted ACK denial.
A deliberate unresolvable function mutation must fail linked-source compiler
admission, not be treated as a passing guest return.

Run `mvn -B -ntp -Dtest=AttoFormalPolicyGuestTest -DfailIfNoTests=true test`
on an exact pinned Java compiler tree. The workflow requires a nonempty JUnit
report and records the triggering head/tree SHA. **Do not claim executed
Java guest evidence until the job actually runs with nonzero steps and passes.**

## Claim boundary

- Evidence here applies only to the **Java/Graal interpreter guest path** with
  developer isolate policy and JIT server execution profile.
- The copied AttoLang source is **not** independent formal proof. The bounded
  safety models and production TS/Dart/Rust/Go/Gleam refinements belong to
  the source owner and continue to require their own CI and negative tests.
- The policy functions are not automatically connected to production telemetry
  delivery or synchronization. No user data, network, OAuth or Redis access
  is involved.
- LLVM, .NET NativeAOT, Wasm, Forge/Cranelift AOT guest parity and actual
  product behavior remain **NOT_EXECUTED**.
- Failing/zero-step CI and lacking independent review are merge holds; never
  treat this source fixture as a promotion receipt.
