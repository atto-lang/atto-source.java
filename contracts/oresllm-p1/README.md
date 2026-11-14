# OresLLM P1 exact-source JVM integration gate

This is a **CI test-only**, temporary mirror of 5 real OresLLM source/guest
program pairs (9 blobs total). Every file's Git blob SHA is verified against
the original, pinned PR head before invoking the actual Java/Graal compiler.

| Source | Pinned PR head | Test |
| --- | --- | --- |
| [ores-llm-tensor.ores](https://github.com/ores-llm/ores-llm-tensor.ores) | `c0373e3565d5b8d6124139121df3b3934761682b` | `tests/native-tensor2-smoke.ores` |
| [ores-autograd.ores](https://github.com/ores-llm/ores-autograd.ores) | `dbdb5596965daf5bf8a6e03fb497f30457191efd` | `tests/native-affine2-grad-smoke.ores` |
| [ores-llm-nn.ores](https://github.com/ores-llm/ores-llm-nn.ores) | `c7e9f57cb116d78fbd51e80264fed2fce22c9643` | `tests/native-tiny-mlp-smoke.ores` |
| [ores-llm-optim.ores](https://github.com/ores-llm/ores-llm-optim.ores) | `668b5a854a518bbec714fad1dfa66b549456280a` | `tests/native-scalar-sgd-smoke.ores` |
| [ores-llm-examples.ores](https://github.com/ores-llm/ores-llm-examples.ores) | `14f988e0c742213827017ab866849bc1b352002f` | `examples/native-sgd-neuron.ores` |

The job builds the **actual** Java launcher with Maven, invokes `--check`,
and then executes the Oreslang guest and checks its output. An admitted
runner is not a pass; inspect executed steps and exact SHA evidence.

No second-source-copy is merge-target production code. If this workflow
cannot obtain a runner or toolchain, report the obstruction and run via a
private alternate carrier with byte-identical source and verified runtime.
Host numerical tests never substitute for the guest. Java guest runtime does
not certify LLVM, WASM, .NET, Forge or native-image AOT.
