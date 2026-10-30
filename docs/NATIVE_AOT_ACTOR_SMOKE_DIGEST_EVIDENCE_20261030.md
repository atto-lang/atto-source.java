# Actor NativeAOT smoke-admission digest pin — executable source-free evidence (2026-10-30)

**Status: source-only, mock-only test. No Java/Graal guest, Maven suite or Native Image executed in this test.**

## Exact code checkpoint

- Java/Graal PR #2246 source/code head: `3f088da096bfdfb7f8f18d6084c15894adf7cd1a`
- Exact source Git tree: `24adc2c9ae584ba6134cbc62c1986037c323d0ab`
- `scripts/verify-native-actor-smoke.sh` Git blob `73c54c1cdb0bba7f6e7c74bf35241bb8f2696b1e`
- `scripts/test-native-actor-smoke-admission.sh` Git blob `23aa246d44e9040c272dcd20fb05922eefc2e729`
- NativeAOT workflow: `.github/workflows/actor-demo-pinned-native-aot-build.yml` now passes `target/ores-aot.sha256` to the actor smoke verifier as an explicit second parameter.

## Local independently executed source-free test

Both exact Bash script bytes were reconstructed from the GitHub connector in a local synthetic Git repository. `git hash-object` verified **both** scripts byte-for-byte against the committed blobs before execution. Bash syntax checks completed successfully. The original script-based mock test runner executed one positive mock case and **16/16 rejected adversarial cases**, printing `NATIVE_ACTOR_SMOKE_ADVERSARIAL_PASS positive=1 negative=16 (mock only)`.

The negatives include invalid compiler syntax diagnostics (incorrect exit code, timeout, signal, missing or misplaced diagnostic, stdout contamination), valid-source check failures, guest exit/error/stdout mismatch, mismatched guest Git blob, symlinked guest source, wrong compiler digest and compiler-byte tampering after digest pinning.

This tests **admission behavior** only, not a trusted compiler's correctness, an actual native build, or all 18 Graal guests. A matching digest of an untrusted arbitrary executable alone is not source/build attestation; the workflow must still prove exact-source NativeAOT build provenance and trusted runner execution.

## Cross-stack and review gate

At this code head, all 14 `src/test/resources/parity/step-demo-v1/*.ores` match Step Functions PR #11 (source code checkpoint `e470c6ddbde515832128bfe3ddbdf45cc6aff511`) by Git blob. The remaining four baseline guests and all fourteen native demo cases require actual Graal `Context.eval` executions with JUnit XML, no skips/failures/errors, exact stdout and compiler-checked `critical[read]/critical[write]` / sealed state contracts.

The source organization has repeatedly returned zero-step Actions failures; a queued workflow is not a passed execution. The separately approved private carrier requirement and conformance security incident #22 prohibit unapproved cross-organization private-source mirroring. Independent reviewer approval and effective protected-main rules are pending. Keep this PR **DRAFT**.
