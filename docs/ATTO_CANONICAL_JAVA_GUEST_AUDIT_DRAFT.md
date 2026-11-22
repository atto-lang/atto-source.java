# Java canonical AttoLang syntax guest-audit draft

This stacked Java branch adds **26 byte-pinned actual `.atto` programs** from
[conformance #665](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/665),
contract commit `2a831f56df6dc92993d2344cbedfe0127b2eeb58`. It does not edit the Java compiler/runtime.

The JUnit 5 `CanonicalMatchTildeConformanceGuestTest` uses the actual
`OresCompiler.parseAndTypeCheck` path and (for accepted cases) real
`Context.eval` Truffle guest execution. Expected negatives must fail before
the guest context is entered. Each fixture is verified against its Git blob SHA-1
before use. A failure is a real outstanding Java contract gap, not skipped or
converted into fake PASS evidence.

Run (actual compiler+Truffle required):

```sh
mvn -B -ntp '-P!native-thread-unix' \
  '-Dtest=CanonicalMatchTildeConformanceGuestTest' \
  -DfailIfNoTests=true test
```

**Evidence boundary:** 26 **candidate** dynamic tests are authored; passing
source-only manifest validation is not a guest execution receipt. No proof yet
for native-image AOT, full-tree source identity, zero-codegen negative artifacts,
Wasm, LLVM, Forge or .NET. Parent Java PR #2727 itself does not contain the
populated-map additions from Java PR #2732, so the suite intentionally exposes
incomplete integration. Existing unrelated tests remain unchanged.
