# Immutable JVM compiler bundle — draft admission procedure

This capability **does not attest** the Oreslang compiler's provenance or any Oreslang-ML semantics. The proposed `.github/workflows/compiler-bundle-proof.yml` intentionally builds on a runner in the **original private** `ores-truffle-oreslang/oreslang-source.java` repository, not in an alternate organization. It does not export private sources or compiler binaries across organizational boundaries. Until source-org Actions execute real steps, the bundle remains hypothetical.

The workflow checks out an exact SHA, verifies the checked-out Git tree, runs isolated fixture tests, builds/tests using the existing GraalVM 25.3 / Java 25 workflow convention (Maven release level 21), copies the Maven **runtime** dependency closure, assembles a modular `OresMain` launcher, and verifies `--build-info` with strict JVM/JIT capability semantics. The launcher and every JAR are SHA-256 inventoried with the source commit, source tree, `pom.xml` digest and toolchain versions. The canonical CLI status is `BUNDLE_BYTES_CHECKED_NOT_ATTESTED`; the manifest explicitly says `provenance_independently_attested=false` and `oreslang_ml_guest_executed=false`.

The stand-alone verifier **must** receive the trusted expected Git commit **and tree**, rather than deriving either expectation from the untrusted bundle. Refuse an artifact whose main JAR, runtime JAR set, launcher script, manifest structure, build mode, or expected SHA differs. For example:

```sh
python3 scripts/ci/compiler_bundle.py verify \
  --bundle /reviewed/private-bundle \
  --source-commit '<independently reviewed 40-hex commit>' \
  --source-tree '<independently reviewed 40-hex Git tree>' \
  --expected-manifest-sha256 '<independently reviewed 64-hex manifest digest>'
```

The receipt is *self-reported*, not a signed reproducible-build or SLSA attestation: it cannot prevent a compromised build, self-consistent malicious files, poisoned Maven dependencies, or a compromised runner. An independent reviewer must pin and attest the complete build inputs, compare the external artifact digest, and operate an isolated trust domain. The script relies on a POSIX JVM launcher and a provisioned Java runtime. Artifact upload remains private to the original source repo and expires after one day.

**Release gates still open:** Actions runner/budget administration, exact source build+test execution, independently reviewed binary provenance, both private source incidents `oreslang-platform-conformance#22` and `#77`, and real positive/negative Java compiler checks and six ML guest executions under the three-repo source lock. This draft is not an approved private-source mirror publisher, and it does not authorize bypassing the existing independent CI security policy.

The new workflow also runs the **real bundled Java OresMain** against a known valid checked-in example (`examples/together.ores`) and a synthetic malformed `.ores` file. It requires successful positive `--check` without stdout, plus a negative exit status `1`, no stdout, and a positioned `path:line:column: error:` diagnostic. This gate is unverified until a source-org runner actually executes the job, and it does not constitute execution of the six ML guest programs.

### Hardening: passive inspection versus opt-in execution

The verifier no longer runs the JVM launcher by default. It inspects the canonical artifact manifest, exact files, runtime JAR closure, Git commit and tree pins, and the caller-supplied expected manifest SHA-256. To actively test a *trusted* launcher, pass `--probe-launcher` separately. An executable with plausible self-reported hashes can still be malicious: hash matching is not a sandbox or a signed compiler provenance claim. The workflow sets a local digest and uses `--probe-launcher` only after compiling and creating the bundle inside the original private source-org runner. An independent reviewer must obtain an expected digest out-of-band before trusting an externally supplied bundle. All artifact ancestor path components must be nonsymlinks.

### Additional JAR archive admission and regression evidence

Both bundle creation and passive verification now require valid ZIP/JAR containers rather than accepting arbitrary bytes with a `.jar` suffix. The main archive must contain `module-info.class` and `dev/oreslang/launcher/OresMain.class`. Duplicate archive entry names and unsafe member paths are denied. These structural checks are not full Java bytecode verification, malware scanning or a trustworthy build attestation.

**Local exact-file evidence:** 32/32 synthetic host tests passed on the committed verifier/tests. The original 19 tests were expanded with nine tests for passive verification, independent manifest pins, symlinked ancestors and index masking, plus four malformed or incomplete JAR cases. A separate private Sagitta Actions job executes ten *independently written synthetic* JVM-artifact policy cases, without any source or binary copying. An authorized source-org Actions job must still run Maven/real Java OresMain and generate independent compiler receipts before deployment or ML guest use.

## ZIP/JAR member validation hardening — 2026-10-18

The passive bundle verifier now refuses executable ZIP prefixes, invalid
individual member CRCs, symlink/special-file metadata, unsafe paths (including
backslashes and parent traversal), unsupported/encrypted compression, entries
over 64 MiB expanded and per-JAR totals over 256 MiB. Entries are streamed to
EOF for CRC verification without extraction or execution.

At exact reviewed Git blobs 2d4d37e04710efcb6765a33ba7f0832d2f2785ca
(module) and c4e28650bb175ec43cc731871e2be708a33965a8 (tests), the
focused local suite passed **39/39** Python host regressions (six new).

Private Sagitta draft PR #30 runs a separate, independently authored synthetic
ZIP oracle without any private Oreslang source, tokens, binaries or downloads.
Run https://github.com/sagitta-stack/sagitta-infra/actions/runs/39164738740
succeeded with 4 hosted test jobs and **35/35 independently modeled checks**
(8 new ZIP cases and 27 existing checks). Its JSON log explicitly reports
private_source_present=false, compiler_executed=false and guest_executed=false.

The original private Java source-org workflow
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39164637670
still FAILED with zero executed job steps, so no verified real compiler artifact
or six ML guest runs exist. Exporting source cross-org remains prohibited while
incidents #22 and #77, trust-root protection and independent approvals remain open.
