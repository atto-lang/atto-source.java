# GenServer host reply-correlation foundation (experimental)

This change is a **host-internal prototype** for future Oreslang OTP-style GenServer support. It is NOT a complete GenServer, not an Oreslang guest-visible class, and not yet wired into ActorRef / actor-mailbox / async-future delivery.

Guarantees implemented in the new internal ledger:
- one opaque per-ledger request ticket with random correlation,
- bounded outstanding requests and atomic close,
- actor identity + runtime ID + incarnation fencing,
- exactly one winner among reply, timeout, cancellation and close,
- no guest callbacks and no serialized CONTROL or reply capabilities.

Missing before genuine GenServer:
- typed Oreslang library behavior and callback ABI;
- actual source actor event dispatch and sealed state;
- runtime-owned OresFuture completion and nonparking await;
- deadline scheduling, cancellation, send ownership and one-shot reply routing;
- supervisor CONTROL failure events, restart strategy, hot reload generation fencing;
- Java+LLVM true guest-execution parity and conformance.

Standalone JDK 21 check from repository root:

    mkdir -p /tmp/ores-genserver-test
    javac -d /tmp/ores-genserver-test src/main/java/dev/oreslang/runtime/GenServerReplyLedger.java src/test/java/dev/oreslang/runtime/GenServerReplyLedgerSmoke.java
    java -cp /tmp/ores-genserver-test dev.oreslang.runtime.GenServerReplyLedgerSmoke

No external code or cross-org private-source mirroring is needed. Local standalone JDK check is not whole-repo CI.

Tracking: https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1205
RFC: https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/65
