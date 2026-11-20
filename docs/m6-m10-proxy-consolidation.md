# M6 / M10 proxy IPC consolidation — experimental host runtime

**Status: DRAFT. The consolidated source has not yet had an executed exact-head CI run.**

Source: [atto-source.java PR #44](https://github.com/atto-lang/atto-source.java/pull/44).
Overlap: [Java #45](https://github.com/atto-lang/atto-source.java/pull/45).
Governance: [atto-spec #40](https://github.com/atto-lang/atto-spec/pull/40);
[conformance #40](https://github.com/atto-lang/atto-platform-conformance/pull/40).

## Single destination receipt authority

* **M6** owns mailbox admission: `try_send_ticket(ref, value, ReceiptRoute)`
  under its runtime gate returns a private ticket **only for ACCEPTED mail**.
  The same `Mail` holds its ticket until dequeue, handler return, handler
  exception or supervised abandonment. On a successful scalar handler return,
  M6 commits `PROCESSED` with that one typed `i64` result. Faults mark that
  mail `FAILED`; queued items after PANIC become `ABANDONED`. Supervisor
  priority CONTROL stays a separate lane.
* **M10** has one nonblocking selector-owned server reactor, up to 64 sessions,
  fixed 84-byte test protocol, a 1,024-entry replay window, bounded output
  queues and per-session five-second timeout. It no longer receives
  independently constructed `CompletableFuture<TurnResult>` completions
  or uses `try_send_tracked`. It calls M6 once on admission, sends ADMITTED
  only after the actual bounded mailbox returned ACCEPTED, and polls **that
  exact M6 runtime ticket** on the reactor thread. Only PROCESSED with a typed
  result emits a positive terminal ACK. FAILED and ABANDONED are distinct.
* The selector uses a 20 ms maximum select interval for ticket checks, does
  not wait on a scheduler carrier, and stops delivering network receipts if
  the client closes or the deadline expires. A disconnect **after possible
  admission is indeterminate**, never a verified REJECTED.
* **No second reply/correlation registry.** This ticket is not the existing
  Atto typed unary application reply registry; that integration is still
  required by Rust #250/#254.

## Security boundary

The M10 proof is a **private same-user UDS and test-secret profile**, not a
TenantVM trust boundary. It checks a 0700 parent, 0600 socket,
`SO_PEERCRED` on accepted and outbound connected sockets, pins the socket's
`fileKey` before close, and only unlinks the originally owned inode.
It authenticates each frame with a provisioned 32-byte HMAC-SHA256 key tied
to the configured actor name. The tenant and fresh server-issued incarnation
are checked. The experimental M10 84-byte frame is missing canonical caller
incarnation, target actor ID, association generation, capability chain,
schema/contract hash and placement epoch. Those missing ticket correlation
fields are filled **only by private host configuration**, not by a guest or
claimed authenticated client identity. They are NOT authorization tokens.
Before production deployment the wire needs full canonical typed identity,
explicit per-tenant capability verification, replay journal/epoch rotation,
peer/key lifecycle, quotas and no insecure transport downgrade.

The restricted M10 compiler accepts **one** source-compiled
`RemoteAdder.on_message(int value)` fixture and generates Java bytecode for
actual execution in OS process B. The sender remains Java HOST test code.
This is not the canonical Atto frontend, not a compiled Atto proxy sender,
not independent LLVM/Rust/Forge/NET/Wasm/JS interoperability and not
Native Image verification.

## Tests and evidence gate

The two-process M10 harness now checks: a genuine second JVM; compiled
restricted Atto receiver SHA; wrong HMAC/tenant/target/association; replay;
typed remote scalar; 12 concurrent clients where each observes only its own
result or explicit FULL; PANIC/FAILED; stale actor association after restart.
The M10 mailbox probe checks exact per-mail STARTED, PROCESSED, FAILED and
ABANDONED and independent supervisor CONTROL. The existing M6 native NIO and
128-ticket probes continue to run.

**Prior-head evidence** (not proof of the newly consolidated source):
* [Java #44 M6 ticket and restricted receiver success](https://github.com/bait-bikes-test/atto-ci/actions/runs/44653461640).
* [Java #45 independent M10 pilot success](https://github.com/bait-bikes-test/atto-ci/actions/runs/44635718533).

**Consolidated exact-head attempts**:
* [bait-bikes-test run #44660093745](https://github.com/bait-bikes-test/atto-ci/actions/runs/44660093745): failed before any job step (0 steps).
* [ores-stack run #44660323082](https://github.com/ores-stack/ores-stack-cli/actions/runs/44660323082): failed before any job step (0 steps).
Neither is a compiler failure, runtime test PASS or evidence for merge readiness.

A passing CI claim needs a checked original Git blob SHA for each imported
source/test/fixture, an executed JDK25 compile (strict lint where possible),
the generated restricted source, both OS processes, native M6 regressions,
concurrent M10 gateway tests, negative authentication/replay/shutdown cases.
Even after those pass, the **24 canonical compiled-guest conformance gates
remain NOT_EXECUTED** until independently compiled `.atto` sender and
receiver programs, a reviewed cross-tenant wire and runtime integration are
actually exercised.

## Follow-on work

1. Get stepful, exact-source JDK25 tests for the combined head; fix failures.
2. Add the canonical typed `ProxyActor` grammar/type checker/lowering and
   compile actual sender `.atto` in process A. Fail closed for unlowered APIs.
3. Replace the test client with an async selector-owned client pool; connect
   the existing unary reply registry instead of creating a second one.
4. Add full authenticated tenant/capability/schema/epoch negotiation and
   resource/ownership fencing. Test restart and ambiguous in-flight cancel.
5. Run independently compiled Java ↔ LLVM ↔ Rust first, then Forge/.NET/
   Cranelift/Wasm/JS using the conformance registry and exact SHA evidence.
