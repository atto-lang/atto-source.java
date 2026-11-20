# GenServer host reply-correlation foundation (experimental)

This change is a **host-internal prototype** for future Oreslang OTP-style GenServer support. It is NOT a complete GenServer, not an Oreslang guest-visible class, and not yet wired into ActorRef / actor-mailbox / async-future delivery.

Guarantees implemented in the new internal ledger:
- one opaque per-ledger request ticket backed by an indexed generational slot,
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


## UUID-free hardening — 2026-10-20

The internal ledger now uses **lazy caller-local slots plus a LIFO freelist**
instead of generating a random UUID for each call or hashing by UUID. A ticket
contains the original ledger identity, runtime/actor/incarnation Target, slot
index and monotonic u32-range generation. Registration is bounded, settlement
is serialized under a short private gate, the exact current Ticket object must
match, and cancellation/reply/close can claim at most once. Slots whose
generation reaches `0xffffffff` are permanently retired rather than wrapping.

A `Target` still contains UUID-valued **existing runtime/actor identities**:
the change removes *per-call* UUID generation, not all UUID types. Ticket is
host-private: the integer slot and generation are NOT reply authorization,
transport credentials, or a valid network protocol. Cross-process correlation
requires separate endpoint/incarnation/session binding, schema checks and
authenticated delivery; do not serialize this Ticket.

`GenServerReplyLedgerGenerationalSmoke` tests ABA-reuse, 10,000 sequential
reuse operations, foreign runtime / restarted actor fencing, and simulated
generation exhaustion; `GenServerReplyLedgerSmoke` retains the 750 existing
reply/cancel/close/register race checks. Standalone JDK21 proof was executed
locally against exact source/test blobs:
- ledger Git blob: `261a853b344a40452c3750977cf8c2a455f6c73d`
- generational smoke Git blob: `50cfea41c696b5368b52dc922cb324d2227da4ed`
- output: `GENSERVER_LEDGER_750_RACES_ABA_10000_PASS` and
  `GENSERVER_GENERATIONAL_ABA_CHURN_EXHAUSTION_PASS`.

To reproduce:

```bash
mkdir -p /tmp/ores-genserver-test
javac -d /tmp/ores-genserver-test \
  src/main/java/dev/oreslang/runtime/GenServerReplyLedger.java \
  src/test/java/dev/oreslang/runtime/GenServerReplyLedgerSmoke.java \
  src/test/java/dev/oreslang/runtime/GenServerReplyLedgerGenerationalSmoke.java
java -cp /tmp/ores-genserver-test dev.oreslang.runtime.GenServerReplyLedgerSmoke
java -cp /tmp/ores-genserver-test dev.oreslang.runtime.GenServerReplyLedgerGenerationalSmoke
```

No JVM-wide OresFuture/Maven tests, guest-source actor execution, supervisor
CONTROL routing, IPC, native-image/AOT or LLVM tests were performed in this
round. **Do not promote this to production without those gates.**
