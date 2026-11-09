# Java/Graal actor-bound f32 host preflight v1 — not a compiler intrinsic

This draft implements a package-private *host-only* f32 preflight candidate for the ABI described in common-source draft PR #256. It does **not** implement GraalVM Native Image FFI, a Java binding to the native OresLLM C pool, or an AttoLang source intrinsic.

The staged `F32ActorHostPreflightV1` is bound to a genuine runtime `ExecutionContext` object (not the numeric IDs supplied by source guests). It requires an actor allocation domain with a nonzero owner and generation, and checks **object identity**, the actual constructing carrier thread, turn retirement, GEMM axes 1..32, matching inner dimension and a 4096-byte output budget. It is synchronized to make inspection/retirement mutually exclusive; the `retireTurn` signal is not yet wired to `ActorRuntime` or its physical dispatch.

### Exact byte-identical source execution

Local JDK **21.0.12.1** `javac --release 21` compiled and Java `-ea` ran **three** exact Git-blob-matched repository source files (all hashes independently verified after reconstruction):

| File | Git blob SHA |
| --- | --- |
| Existing `src/main/java/dev/oreslang/runtime/ExecutionContext.java` | `bff7ed59bad105e2b0548921600caec7bb724dbe` |
| New `src/main/java/dev/oreslang/runtime/F32ActorHostPreflightV1.java` | `5777891a3f46da4e3e412b915b08eb725aaff9b4` |
| New `tests/F32ActorHostPreflightV1Smoke.java` | `9b0545bb6f71674ee2ba91ff2e76c0ef5d214f2a` |

The executable validates golden/matrix-size preflight, budget, invalid/oversize axes, forged second context object with **identical numeric identity**, foreign execution, a copied context on a second OS thread, repeated retirement, denial to task-owned domains and preservation of true physical owner.

Result:

```text
ORES_F32_JAVA_HOST_PREFLIGHT_V1_PASS|stolen_thread=DENIED|retired=DENIED|guest=NOT_EXECUTED
```

The **exact committed standalone source-org runner** `tests/run-f32-actor-host-preflight-v1.sh` (Git blob `3f304983c9373f0bd0ea52db572fffa17a017333`) was also reconstructed byte-for-byte, passed `bash -n`, and **executed locally** using the same three exact source Git blobs without Maven/network. It emitted `ORES_F32_JAVA_ADMISSION_MATRIX_PASS|jdk=21|guest=NOT_EXECUTED|native_image=NOT_EXECUTED`. It has **not** executed in GitHub-hosted CI.

### Not yet implemented or certified

This is an **internal staging guard**; `HOST_PREFLIGHT_ONLY` is not native host call permission. The runtime must mint/revoke it from its **real actor turn dispatcher**, fence STOP against completion, own the tensor/pool pointers privately, bind the trusted native bridge, and invoke actual GEMM only after a lifetime pin is held throughout the call. Guest-supplied IDs must never become host authority. In addition, the compiler must lower typechecked `rt borrow`/`rt take` and produce compile-negative ownership diagnostics.

No Java/GraalVM Native Image build, guest GEMM compile/execute, external-effect authorization or protected tenant isolation is claimed. Source-org hosted CI remains an independent exact-head verification gate. No private source was copied across GitHub organizations; the existing OresLLM compiler-source incident remains open.
