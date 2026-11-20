# Local on-device Oreslang build command — experimental LLVM host subset

The Oreslang Java/Graal launcher now accepts a distinct developer command:

    oreslang build source/main.ores dist/app

The command delegates to the already existing LLVM backend script
scripts/compile-aot.sh (from oreslang-source.llvm), which uses an installed
oreslang-llvmc compiler and Clang to lower supported Oreslang source to real
host LLVM IR and then a native executable. No fallback to interpretation or
to a checked-source bundle is allowed. Unsupported Oreslang constructs fail.

Preconditions: build the LLVM source backend and install Clang; create the
output parent directory; choose a new output pathname. Supply ABSOLUTE paths
to the approved local binaries and LLVM script:

    export ORESLANG_LLVM_AOT_DRIVER=/absolute/path/to/oreslang-source.llvm/scripts/compile-aot.sh
    export ORESLANG_LLVMC=/absolute/path/to/oreslang-source.llvm/build/oreslang-llvmc
    export CLANG=/absolute/path/to/clang
    oreslang build /absolute/project/src/main.ores /absolute/project/dist/app

The Java launcher is the command entrypoint. The LLVM driver is allowed only
on the local user device, and inherits a minimal environment; inherited
cloud/device-auth API credentials are intentionally excluded. It publishes
a local JSON receipt with sha256 digests of the source file, LLVM compiler,
Clang, AOT driver and final executable. It rejects missing tools, a
pre-existing output path, unsupported source, compiler failures and source/
toolchain mutation detected between pre/post build hashing.

IMPORTANT: the receipt always says trust=untrusted-self-report. This command
does not enroll a device key, fetch an API upload capability, sign DSSE, seal
the source tree, pin imported dependencies, verify the full set of toolchain
inputs, produce a reproducible-build proof, provide a sandbox, or upload bytes
to the API. SHA256 is an integrity fingerprint, not proof of compilation.
The local build process itself is controlled by the user; a compromised
device can lie or tamper with artifacts. Only independently verified
evidence may promote any build to a trusted release.

Testing: DeviceBuildCommandTest uses a FAKE local driver to test command
admission and credential scrubbing. A standalone Java compilation and
mock-driver process exercise is also possible. Neither proves that the real
LLVM backend builds this source. Follow-on CI must build the exact LLVM
compiler, use the actual compile-aot.sh and Clang with an admitted guest
fixture, execute the native program and compare expected exit/output.
Source org Actions runs with zero allocated steps are not evidence of a pass.

The Java/Graal app-specific full-AOT artifact path is not yet implemented.
The LLVM path handles only the implemented host subset, NOT the full actor,
Spin, async HTTP or AI agent language. WASM, NET and Forge require their own
artifact-producing adapters. A future flags-2-env schema must govern
additional subcommand options; v1 accepts two positional filenames only.

API counterpart:
https://github.com/ores-truffle-oreslang/oreslang-api-server.ores/pull/1

Reference signed upload validator:
https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/223
