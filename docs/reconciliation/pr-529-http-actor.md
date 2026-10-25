# Semantic resolution of historical HTTP actor handoff PR #529

Historical head: `a51bb2709c8ec080a5490c13cdef214c24c2b4a5`. Integrated baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

- Keep current-main `NativeHttp` request-scoped abort and transport-closure release, `poolWorker` lifecycle, cancellation/actor-death revocation, and its newer actor-pool tests. Do **not** copy the historical transport state machine over main's concurrency and finalization fixes.
- Forward-port exactly three missing request-reuse integration tests from historical `NativeHttpTest`: distinct long-lived actor streaming; abort one request without killing another; release a single listener slot while a persistent actor remains alive. Current main's existing test and helper implementations remain authoritative.
- Preserve historical design proposals in `ACTOR_SPAWN_SPEC_DESIGN.md` and `HTTP_ACTOR_ENVELOPES.md` as **non-executable design**, not a declaration that `spawn new`, typed transport actors, or per-instance policies are accepted by the current compiler.
- Do not restore old parser/evaluator code for `spawn new` without a separate reviewed compiler/type/ownership lowering and regression. Keep an explicit follow-up for that syntax rather than backporting an obsolete frontend wholesale.

**Gate:** exact-head Maven/Graal integration tests for three reused-actor requests, cancelled responses, ownership and admission release must run before merge. Current main's newer security/supervision contracts take precedence; Git conflict resolution is not a CI pass.
