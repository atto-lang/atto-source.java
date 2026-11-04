## Summary

Describe the implementation and any compatibility/security impact.

## AttoLang-first implementation disclosure

Fill the following **three single-line fields** for PRs that add or modify `.java` files. The gate runs on PRs created after the November 4, 2026 policy merge. Do not leave TODO placeholders. Explain which changed `.atto` modules contain the guest functionality. For a legitimate Java-only compiler/host change, write `none: [specific technical reason]` after the first field; justify the Java scope explicitly.

AttoLang guest implementation:

Java host/compiler exception:

Guest test evidence:

## Verification and parity

Report the exact-head AttoLang compiler/guest test invocation and output, or `NOT EXECUTED: [reason]`. Note cross-backend Java/LLVM/Wasm/Forge/.NET follow-up. Source-only assertions, synthetic runners, and zero-step CI never establish guest execution.
