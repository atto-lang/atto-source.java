# Java/Graal bounded Result<int,Error> runtime foundation

HOST_ONLY: AttoIntParseRuntime implements signed-i64 ASCII integer parsing as
a sealed Ok/Err Java value with an immutable, reference-identical Error. Each
trusted TenantParser is scoped to one tenant ID; no Error is authorized for
another tenant. Strings are limited to 1,024 ASCII bytes and checked without
locale-dependent Long.parseLong, reflection, Java exceptions on invalid input,
or overflow. Error allocation exhaustion remains fatal, not parse Err.

The AttoAdtPatternPlanner verifies Ok()/Err() payload-discard tags but is a
host-only planner. This repository does NOT yet emit compiled Atto guest code
for Result, ?, trap, or int.parse; the new JVM and Graal Native Image tests
must never be reported as guest parity. Track spec #124, conformance #68 and
parity #20. Compiler lowering, tenant-safe guest Error handles, and exact SHA
real .atto guest testing are required next.
