# Java/Graal platform-core-v1 parity candidate execution gate

The seven positive/five negative candidate `contracts/platform-core-v1`
files are shared exactly with LLVM and Wasm (same immutable Git blob IDs).
Rust imports the same files in draft [rs #70](https://github.com/ores-truffle-oreslang/oreslang-source.rs/pull/70).

`PlatformCoreV1ScalarGuestParityTest` independently verifies all 12 Git
source blob IDs plus manifest identity. For the seven positive cases:

1. Real `OresCompiler.parseAndTypeCheck` must admit the **unchanged** source.
2. `LinkedProgramRunner.validate` must link one exact source unit.
3. A **separately reported source transform** wraps the same signed-i64
   expression in `stdio.stdout.write(...)` so the Graal guest's actual
   full-width signed result is observed. The output is compared to the
   canonical manifest expectations, including signed i64 min/max boundaries.

The negative cases must not be silently admitted. Four must reject during
compiler parsing/type checking. The overflow source must be admitted by the
typechecker and then fail during real guest evaluation; a successful wrapped
value is a divergence. Whether runtime errors map to the shared
`throw/raise/panic` and actor supervisor CONTROL contract is a **separate**
unverified test requirement. The current test is a Java/Graal interpreter/JIT
guest check, **not** Native Image AOT.

Run once the source-org runners and review gates are restored:

```sh
mvn -Dtest=PlatformCoreV1ScalarGuestParityTest test
```

Then compare **executed** exact-head Java/LLVM/Rust/Wasm results with one
signed-i64 expectation and diagnostic normalization. The transformed Java
stdout guest is **not** byte-identical original-source execution; LLVM's
new candidate draft [#1836](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1836)
uses the original source and an object-level wrapper rename to prove the
complete signed-i64 guest result. Wasm draft
[#495](https://github.com/ores-truffle-oreslang/oreslang-source.wasm/pull/495)
executes original sources through a digest-pinned LLVM-to-wasm32 path.
These drafts are not executed source-org evidence yet.

The scalar suite proves none of these platform integration surfaces:
mobile JNI/iOS handoff, IoT authenticated stream adapter, desktop resize
event callbacks, embedded ISR queue, FPGA source-to-RTL or ASIC provenance
pipeline. Those require separate type/ownership/permission admission,
versioned FFI/target ABIs, actual guest calls and board/device/build logs.
It also does not prove actor state, ownership, supervision, channels,
async, errors, real-time or package-import parity.

Keep this draft unmerged until private-source CI and protected-main,
independent review and conformance incidents #22/#77 release holds are
satisfied. No unapproved cross-organization private-source mirrors.
