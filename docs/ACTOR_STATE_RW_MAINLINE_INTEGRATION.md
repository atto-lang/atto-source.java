# Canonical actor state and unified critical admission — integration gate

Status: **runtime-admission substrate only**. This branch does not enable actor-state source lowering, `critical-read` or `critical-write` declarations, or assert general Java/LLVM parity.

## Pre-1.0 language contract

- `with` in a signature declares an upper bound of permitted effects, e.g. `with state.read` or `with state.read, state.write`. It grants **no** state capability by itself.
- `using` acquires a lexically scoped state capability, and **never** declares signature effects.
- `self.handle_state(|s| -> ...)` and `using state.read(|s| -> ...)` are canonical, immutable reads.
- `self.handle_state(critical |s| -> ...)` and `using state.write(critical |s| -> ...)` are canonical exclusive writes.
- Actor instance state resides in the single compiler-owned fixed-key `state { ... }` map, never ordinary actor fields or direct `self.state`.
- A state loan cannot escape or survive `await`, `select`, `rt cooperate`, cancellation, or any suspension boundary. Compile-time bounds **and** runtime owner/incarnation checks are mandatory.
- One actor executes at most one guest-code turn on a native thread at any instant. Concurrent logical READ tickets do not relax actor single-flight execution.

## Admission invariant

A single `CriticalGate` owns each protection domain. Old/implicit `critical` uses `CriticalGate.newTicket()` (WRITE); explicit READ/WRITE uses `gate.rwGate().newTicket(mode)` on the exact same domain instance. There must be no independently constructed RW gate protecting that domain.

- FIFO reader cohorts; writer barriers stop later arriving readers; no writer/writer or writer/reader overlap.
- Logical task ownership, not carrier thread identity, controls admission.
- A ticket is terminally bound to **one** completion future; a separate future may never release it.
- Reentrant admission and read-to-write upgrades fail closed until correctly specified.
- A successful admission Future alone does not authorize a guest continuation after actor STOP. Every resumed use must revalidate the ticket, state owner identity, and actor incarnation.
- Actor STOP / shutdown revokes queued and granted tickets; release and cancellation are idempotent.
- Internal monitors may protect short metadata updates, never block guest code or park actor/scheduler carriers.

## Before source admission or merge

1. Validate this exact current-main-derived Git tree with full JDK25 Maven/JUnit (including `CriticalGate*`, `CriticalRwGateTest`, mixed mode and deadlock regression), plus GraalVM Native Image AOT/hybrid.
2. Wire evaluator per-object/per-class/per-file `critical` dispatch to the unified gate without bypass. Integrate the canonical Java actor-state compiler branch without overwriting newer main changes.
3. Prove sealed `handle_state`, effects, loan provenance, non-escape, and cancellation/STOP/incarnation fencing end-to-end. Unsupported forms must fail closed meanwhile.
4. Integrate LLVM counterpart; run positive/negative compiled guest differential tests, original-tree LLVM CTest and ASan/UBSan, including typed 15-actor directional pipeline fixtures.
5. Revalidate **both actual integrated main trees** after merge. Funding mirrors must be pinned to the exact source Git tree/blob SHAs; smoke subsets are not equivalent to full builds.

This file describes required invariants, **not** a claim that items 1–5 already pass.
