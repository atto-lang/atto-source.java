# Java allocator parity-v1 guest evidence

This subset implements **seven of the 22 allocation contract cases** with
backend-owned source fixtures. The TSV manifest maps each contract case to a
real .atto program. The JUnit harness imports the compiler's own closed
`atto.alloc/allocator` declarations, validates, executes the linked guest,
requires exit code 42, then computes and emits its exact Git fixture blob SHA.

Negative contract outcomes (`REJECT`) here are deliberately checked
operation rejections: the running guest must observe `false` or `-1` from
an invalid policy request. This is **not** compiler-negative validation
and does not imply any physical deallocation, opaque receipt authority or
automatic destructor execution.

The CI job checks the exact PR source SHA, JUnit XML and all seven receipts
before printing `ATTO_ALLOC_GUEST_EXECUTED`. Its run/job/fixture evidence
must still be checked independently against the complete Git source tree.
No source-only grepping or host reference model counts as guest execution.

All other parity-v1 cases remain **NOT_EXECUTED**, including move/borrow
compile-time rejections, real native allocator authority, automatic
lexical drops, Arc/Weak and actor quiescence. Never fill those results
with fabricated PASS or with a `.atto` file that only sketches semantics.

The library sources stay independent in each compiler repository.
