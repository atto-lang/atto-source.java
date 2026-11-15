# Borrow regression audit — 2026-11-10

Base: `6d8eb70048eafc9a84c5aebe9c1bb06104f16e76`.

The existing borrow/ownership selection ran 220 tests with four failures.
Two tests used historical `rt borrow mut` fixtures but expected semantic
ownership results; an overlap test could pass on the parser's obsolete-syntax
diagnostic without checking loans. Historical fixtures remain byte-identical
and now assert syntax rejection. New `.atto` fixtures parse before negative
typechecking assertions, so a parser error cannot masquerade as ownership
checking. Two other assertions now match the current lifetime/capture messages.

Local `mvn -q -Dtest='*Borrow*,*Ownership*' test` passes **222 tests**, zero
failures/errors/skips. This includes actual Truffle guest evaluation of the
mutable-owner fixture, returning 42. The local JDK 27 and GraalVM 25.0.4 both
use Truffle's fallback interpreter with polyglot 25.3.4.1; this is hosted guest
execution, not optimizing-JIT or Native Image evidence.

These exact new source blobs were independently compiled by LLVM built from
`0ee037ca3e5dd9ac6ac1e9cc3d40f19ee6326a9c`:

| Fixture | Git blob | Executed outcome |
| --- | --- | --- |
| immutable-owner.atto | `debcef23bf39b07d3f5bcf3a1b8f0aeaa7607b0b` | Java/LLVM reject immutable owner |
| mutable-owner.atto | `c56b9e63174ff2fbb762e71273c032c6473f490a` | Java returns 42; native LLVM returns 42 at O0/O2, each with and without ASan/UBSan |
| overlap.atto | `18cdecd7bac1a7e2ede50dee0260f381ceae760e` | Java/LLVM reject overlapping loans |

LLVM compiler binary SHA-256:
`0761945ba51ed85e3896f522f6401fbabc993fcd2b7e353a9641c9a7b86a5f1f`.
The compiler was built with LLVM 20; executable checks used Clang 23. LLVM
20's ASan runtime stalled during initialization on this macOS installation,
before guest execution; that attempt is not a passing sanitizer result.

This proves only these executed cases and the selected regression suite.
It does not certify arbitrary programs, shared Rust adapter equivalence,
Wasm/.NET ownership support, or runtime race freedom. No hosted Actions or
Native Image execution is claimed here.
