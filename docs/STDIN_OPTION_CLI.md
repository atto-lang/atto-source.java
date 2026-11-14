# Explicit CLI stdin and typed EOF

`stdio.stdin.read_line()` returns `Option<String>`: a blank line is `Some("")`,
a line carries its text without its terminator, and EOF is `None`. The previous
checker declared `String` despite the existing runtime Option implementation.

The CLI now explicitly supplies its host-owned `System.in` when its policy has
STDIN. `LinkedProgramRunner.runWithInput` requires STDIN before parsing or reading,
accepts a host InputStream, and leaves stream ownership with the caller. Existing
embedding overloads retain the restricted builder's empty input; no process-wide
input is exposed to hosted/untrusted guests. The stdin primitive still checks its
runtime capability. No new parsing, Rx operator, buffering or application policy
is implemented in Java.

Guest regression: `src/test/resources/oreslang/stdin-option.atto`; JUnit supplies
input and checks blank/value/EOF, explicit linked input, stream ownership,
capability denial before consumption, and rejection of direct String binding.

Local validation uses GraalVM JDK 25, Maven and the exact branch tree. Run:

```
mvn -q -Dtest=StdinOptionResultTest,StdioLogAndSpreadTest,SourceTryFinallyTest test
```

This does not certify Native Image, LLVM, Wasm, Forge or .NET. Rx integration
retains known imported-inheritance and synchronous callback scheduler blockers;
those are separate from this bounded stdin fix.
