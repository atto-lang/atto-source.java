# Immutable JVM compiler bundle — draft admission procedure

This capability **does not attest** the Oreslang compiler's provenance or any Oreslang-ML semantics. The proposed `.github/workflows/compiler-bundle-proof.yml` intentionally builds on a runner in the **original private** `ores-truffle-oreslang/oreslang-source.java` repository, not in an alternate organization. It does not export private sources or compiler binaries across organizational boundaries. Until source-org Actions execute real steps, the bundle remains hypothetical.

The workflow checks out an exact SHA, verifies the checked-out Git tree, runs isolated fixture tests, builds/tests using the existing GraalVM 25.3 / Java 25 workflow convention (Maven release level 21), copies the Maven **runtime** dependency closure, assembles a modular `OresMain` launcher, and verifies `--build-info` with strict JVM/JIT capability semantics. The launcher and every JAR are SHA-256 inventoried with the source commit, source tree, `pom.xml` digest and toolchain versions. The canonical CLI status is `BUNDLE_BYTES_CHECKED_NOT_ATTESTED`; the manifest explicitly says `provenance_independently_attested=false` and `oreslang_ml_guest_executed=false`.

The stand-alone verifier **must** receive the trusted expected Git commit **and tree**, rather than deriving either expectation from the untrusted bundle. Refuse an artifact whose main JAR, runtime JAR set, launcher script, manifest structure, build mode, or expected SHA differs. For example:

```sh
python3 scripts/ci/compiler_bundle.py verify \
  --bundle /reviewed/private-bundle \
  --source-commit '<independently reviewed 40-hex commit>' \
  --source-tree '<independently reviewed 40-hex Git tree>'
```

The receipt is *self-reported*, not a signed reproducible-build or SLSA attestation: it cannot prevent a compromised build, self-consistent malicious files, poisoned Maven dependencies, or a compromised runner. An independent reviewer must pin and attest the complete build inputs, compare the external artifact digest, and operate an isolated trust domain. The script relies on a POSIX JVM launcher and a provisioned Java runtime. Artifact upload remains private to the original source repo and expires after one day.

**Release gates still open:** Actions runner/budget administration, exact source build+test execution, independently reviewed binary provenance, both private source incidents `oreslang-platform-conformance#22` and `#77`, and real positive/negative Java compiler checks and six ML guest executions under the three-repo source lock. This draft is not an approved private-source mirror publisher, and it does not authorize bypassing the existing independent CI security policy.

The new workflow also runs the **real bundled Java OresMain** against a known valid checked-in example (`examples/together.ores`) and a synthetic malformed `.ores` file. It requires successful positive `--check` without stdout, plus a negative exit status `1`, no stdout, and a positioned `path:line:column: error:` diagnostic. This gate is unverified until a source-org runner actually executes the job, and it does not constitute execution of the six ML guest programs.
