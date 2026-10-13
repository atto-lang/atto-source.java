# Native defer/try/finally experiments (not conformance cases)

The three Oreslang programs in this directory were previously placed inside
`fixtures/`, which is a **closed, exactly registered** fixture inventory in
`cases.json`. They have no registered cases, and therefore broke
`run.validate_suite()` before compiler admission or semantic parity checks.

These are preserved as **experimental native compiler probes**, not passing
conformance proofs. In particular their expected process exit results are not
registered or verified against both Java and LLVM, and some exceed the
conformance runner's bounded 0–125 exit-code oracle.

Do **not** add them to `cases.json` as passing or pending-on-both cases, or
relax the suite's strict inventory check. Once a correct bounded result
fixture exists and both backends can run it, migrate it into
`fixtures/` with a fully specified `cases.json` entry and backend parity
verification. Until then these files are excluded from the strict conformance
oracle, while their source remains available for native compiler development.
