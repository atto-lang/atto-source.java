# AttoLang actor module-contract v1: reusable guest fixtures

Status: **source committed, NOT EXECUTED**. This is a portable intended
conformance corpus, not parity evidence. Only the Java frontend has the
stage-one feature proposed in its draft PR. Execute the fixtures against the
real exact-head compiler, never a replacement regex/parser or host-language
model.

| Fixture | Expected result |
| --- | --- |
| `accept_public_actor.atto` | Compile; guest `main()` returns integer 42 |
| `reject_private_actor.atto` | Reject: missing required public actor |
| `reject_class_impostor.atto` | Reject: non-actor class is not an actor |
| `reject_generic_actor.atto` | Reject: generic actor cannot satisfy non-generic requirement |
| `reject_inherited_name_collision.atto` | Reject: inherited actor requirement collides with callable |

Compiler invocations and diagnostic matching must use each backend's real
source input pipeline. Record compiler version/SHA-256, backend, complete
fixture Git blob SHA, PR head/root tree SHA, executed steps, exit status,
expected/actual diagnostics and guest result. For the accepted case, static
compilation alone is not guest execution. Rejecting syntax in LLVM, Wasm,
.NET or Forge because the feature is unimplemented is **not** successful
behavioral parity; keep feature support marked unproven.

No source-level external receiver `mtd` support, actor state-loan
capabilities, or typed runtime dispatch is claimed by these fixtures.
Preserve canonical `.atto` and legacy `.ores` compatibility.
