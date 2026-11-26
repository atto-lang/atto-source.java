# Standalone checked integer exponentiation oracle

Run from the repository root with a JDK 21 or newer, without Maven dependencies:

```sh
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -d "$work" src/main/java/dev/oreslang/runtime/StrictIntegerArithmetic.java tests/formal/PowerProbe.java
java -cp "$work" PowerProbe
```

The probe exercises the actual Java backend arithmetic helper using independent BigInteger results, stratified random inputs and signed endpoints. Local JDK 21.0.12.1: 100,845 bounded-exponent comparisons (26,689 exact successful values and 74,156 correct overflow denials), 13 negative-exponent denials, and three huge-exponent identities. Verified helper Git blob 78dd287d195d789ada397701b1de48e4a310bdf9 and probe blob 0c8a1d115ee32329551ac79ac4bf2d58ac6b869d. This is a host-helper regression, not compiler-emitted guest, Native Image, or cross-backend certification.
