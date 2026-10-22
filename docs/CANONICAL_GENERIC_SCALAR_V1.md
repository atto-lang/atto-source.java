# Canonical scalar generics: Java JVM / GraalVM Native Image consumer

The normative corpus and *actual guest execution* verifier reside exclusively in [oreslang-common-source](https://github.com/ores-truffle-oreslang/oreslang-common-source/tree/0e9ee65a3877fa92bcef238234d8380fc5de0967/contracts/v1/generic-scalar) (merged PR #32). Java consumes those files from a **separate Git checkout** rather than maintaining a duplicate fixture registry.

`scripts/verify_canonical_generic_scalar.py` fails closed unless the common-source root Git tree SHA is `858c37d48a4c3a886260b26320bb0dd931b228ab`, the contract subtree SHA is `fd5301ebd354049917e2ecaf1da920b661d6d829`, and the fixture-manifest SHA-256 from the canonical verifier is `80a5053f8ba147346793a209e4f8ec04b8daf4cedb6a149fb1d127ea4af4ffb5` (20 cases). A dirty checkout, missing checkout, symlinked verifier, or changed fixture fails before guest execution. No credentials are requested or copied.

```bash
git -C ../oreslang-common-source checkout --detach 0e9ee65a3877fa92bcef238234d8380fc5de0967

# JVM: uses the real Java launcher and resolved Maven classpath.
mvn -B -ntp -DskipTests package
mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt
python3 scripts/verify_canonical_generic_scalar.py \
  --common-source-root ../oreslang-common-source --backend jvm \
  --report target/generic-scalar-jvm.json

# GraalVM 25 / Java 25 must provide native-image:
mvn -B -ntp -Pnative-aot -DskipTests -Dnative.image.optimization=-O0 package
python3 scripts/verify_canonical_generic_scalar.py \
  --common-source-root ../oreslang-common-source --backend native-image \
  --native-binary ./target/ores-aot --report target/generic-scalar-native-image.json
```

Both modes delegate to `oreslang-common-source/scripts/verify_generic_guest_parity.py`. JVM uses `contracts/v1/java_probe.py` with real `--execute` guest exit statuses; Native Image requires a built Oreslang AOT executable and checks `--build-info`. There are **19 required** Java cases; `generic.scalar.nonscalar-native-rejected` is intentionally pending because Java admits a `bool` specialization that LLVM's scalar subset rejects. The common-source `verify.py --backend graal` does **source admission only** and is not sufficient to assert runtime parity.

For private multi-repo GitHub Actions, mirror exact source Git trees into an approved private runner repository and pass the `common` directory directly. Never forward a PAT, cross-org checkout token, or private source to a public repository.

This does not certify generic methods, classes, records, actors, `Option<T>`, `Result<T,E>`, ownership or optimized Native Image performance. Those require new stage-two contracts and executable differential tests.
