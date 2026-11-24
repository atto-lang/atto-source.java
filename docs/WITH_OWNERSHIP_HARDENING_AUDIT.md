# AttoLang `with`: ownership-hardening audit and proof obligations

Status: **draft follow-up stacked on PR #2832**. Actual new production
runtime guard and new guest `.atto` fixtures are present, but **this
follow-up's full Java/AOT execution is NOT_EXECUTED** due to unavailable
source-org Actions minutes and unapproved alternative source transfers.

## Runtime trust-boundary hardening

The first nominal copy implementation in PR #2832 established static field
layout certification for primitive-only immutable product classes.
This follow-up hardens the *runtime payload* check before a new class
object is constructed:

1. Reject the source object's foreign `Evaluator`/arena owner.
   Cross-actor or cross-isolate payloads require a real serializable/sendable
   transfer contract, not copying a JVM object handle.
2. For every declared scalar field, require a corresponding immutable
   runtime primitive representation: `int` = Long/Integer/BigInteger,
   `bool` = Boolean, `float` = Double/Float, and
   `string`/`String` = String.
   Reject null, uninitialized fields, mutable objects hidden behind
   primitive type declarations, missing fields and additional fields.
3. Preserve the existing static obligations: no user class field-copy
   inference, no arbitrary deep cloning, no constructors/inheritance,
   no mutable fields, no actor-state snapshots via `with`, no borrowed
   or guard-bearing payloads.
4. Preserve `self` as the only implicit receiver and require explicit
   `return`.

These are independent fail-closed runtime checks, **not** a substitute for
typechecking, ownership checking or formal correctness proofs.

## Required invariants to establish across all backends

| Invariant | Expected property | Status |
| --- | --- | --- |
| Receiver ownership | `rt take p` consumes `p`; later access fails | Previously verified Java subset |
| Alias preservation | `rt copy p` creates an independent nominal instance; original data unchanged | Previously verified Java subset |
| Borrow exclusion | Cannot take a live borrowed owner or escape actor `self` | Previously verified Java subset |
| New payload authority | A class slot declared scalar cannot smuggle mutable/foreign owner identity into its copy | Runtime guard added; not executed |
| Atomic reconstruction | All replacement RHSs see original values, publish fresh value only on success | Previously verified Java subset |
| Unwind/drop once | Replaced move-only field is dropped exactly once on failure/success | NOT IMPLEMENTED |
| Suspension safety | No loan crosses `await`/select during partial reconstruction | Checked subset only |
| Cross-backend parity | Exact same guest positive/negative cases execute on Java, LLVM, .NET AOT, Wasm and Forge | NOT EXECUTED |

## Test matrix in this follow-up

Seven new real `.atto` fixtures with a JUnit guest harness:
- Mixed immutable primitive class copy (`int`, `bool`, `float`, `string`)
- Nested readonly structural record copy
- Direct `rt copy` class with original value retention
- Borrowed receiver cannot regain owned copy authority
- Potentially throwing division RHS is rejected before consumption
- Nested nominal owner field cannot be silently cloned
- `self` is canonical; `this` cannot impersonate the implicit receiver

These guest cases are currently **NOT_EXECUTED**. Do not promote an old
passing run on a different source SHA as proof for this PR.

## CI and security provenance

- Earlier feature PR #2832 has a **PASS** of 19/19 Java subset tests:
  [private alternate run #42958096906](https://github.com/voxletra/voxletra-e2e/actions/runs/42958096906).
  That run predates this follow-up and verified a 348-blob selected subset.
- Exact-tree security gate is separately proposed in
  [draft PR #2867](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2867).
  Its source-free synthetic verifier
  [passed](https://github.com/voxletra/voxletra-e2e/actions/runs/42966487656)
  without copying private Oreslang source.
- The independent source-free, bounded ownership transition model
  [passed on private CI run #42967910541](https://github.com/voxletra/voxletra-e2e/actions/runs/42967910541):
  6,947 reachable transition states enumerated at bound five, with 10
  deliberately unsafe controls rejected. This is **model-only evidence**,
  not refinement of the actual compiler/ownership/runtime implementation,
  and carries zero private source files.
- A complete source run is blocked by `MAX_FILES=1024` versus the
  1,910-source-blob current tree, plus mandatory independent
  `@ORESoftware` carrier approval/V3 receipt. Do not manually bypass.
- Full Java source tests, GraalVM Native Image, LLVM/.NET/Wasm/Forge,
  sanitizer-based destructor assertions and model/implementation refinement
  are **NOT_EXECUTED**.

No merge until the runtime scalar protections and new guest tests execute
against an independently authorized exact-source mirror. Do not broaden
nominal `Copy` to affine resources without explicit `drop`/unwind proof.
