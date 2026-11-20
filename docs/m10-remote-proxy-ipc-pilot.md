# M10 restricted Atto source actor over real inter-process IPC

> **Consolidation notice (2026-11-19):** This document originated in independent Java #45 and its reported passing run refers to the **pre-consolidation** source. Java #44 now contains the M10 selector integrated with M6-issued exact-message `Ticket` receipts rather than the original `try_send_tracked` completion futures. A full-Git-tree **JDK 25 executable combined integration** has since passed on a temporary private alternate runner; see [M6/M10 consolidation](m6-m10-proxy-consolidation.md) and current Java #44 PR evidence for the original source SHA, Git tree identity, negative cases and scope limits. This is still a restricted source-compiled sender and receiver over a trusted-host bridge, not canonical Atto guest parity. This document's original evidence is historical only.

**Status: experimental restricted-source JVM pilot, verified host runtime; NOT a general proxy actor, canonical compiler, production remoting ABI, authenticated peer deployment, GraalVM Native Image, or backend parity.**

## What is actually implemented

The consolidated M6 host runtime has an individually correlated mailbox `Mail` carrying a private `Ticket`, minted atomically by `try_send_ticket(ref, payload, ReceiptRoute)`. Only `Admission.ACCEPTED` under the destination actor's gate returns a ticket. The M6 carrier marks that *same* message STARTED and later PROCESSED with its own scalar result, or FAILED/ABANDONED as appropriate. The supervisor PANIC lane remains independent. The host-only `ReceiveWithResult` interface returns a scalar `long`. M10 does not use a second host-completion future.

`AttoM10ProxyIpcGateway` uses one nonblocking NIO selector thread, a bounded 64-session Unix-domain-socket listener in a private parent directory, fixed-size 84-byte frames, per-direction HMAC-SHA256 integrity tied to the **configured actor name**, explicit tenant and fresh server-issued actor incarnation, correlation ID and attempt, bounded 1,024-request in-memory replay table, separate destination `ADMITTED` and `PROCESSED/FAILED/ABANDONED` frames, and a 5-second session budget. The selector polls the *exact runtime ticket* without parking actor carriers or trusting completion callbacks. 0700 parent, 0600 socket, peer credential checks and inode-pinned unlink add local fencing; no cross-tenant authorization is thereby established.

The **client pilot** is `send_request_blocking` and must be called only from a trusted host test thread; it deliberately refuses execution on named M6 actor-carrier threads. It is **not** the eventual nonblocking proxy client, does not multiplex requests on connections, does not implement streaming, `ask` generic typed result/error values, dynamic ActorRegistry discovery, remote lifecycle watch, service redistribution, durable provider or interoperable wire schema. A second independent OS process hosts the real M6 bounded scheduler and receive turn.

The Java-only `AttoM10RestrictedActorCompiler` translates **one exact constrained source grammar** (with strict whole-file matching) into a generated Java class before launch:

~~~atto
define actor RemoteAdder as {
    pub fnc on_message(int value): int {
        return value + 10;
    }
}
~~~

Only that form, with a bounded positive literal increment, is accepted. Everything else rejects. This is source-compiled rather than a host echo mock, but **not** the Atto canonical frontend or the legacy `define proxy actor` grammar. The second JVM runs `M10GeneratedActor.new_handler()` as an M6 `ReceiveWithResult`, and the remote result is a checked `Math.addExact` turn return.

## Executed evidence

- **Historical pre-consolidation only:** Funded private carrier [bait-bikes-test/atto-ci run #44635718533](https://github.com/bait-bikes-test/atto-ci/actions/runs/44635718533), branch `ci/atto-m10-java-proxy-20261119`, head `73234d69310be2ef587097c7034501d5a933b239`.
- Temurin **JDK 25.0.5** with `--release 21`, real `javac`, generated source compilation and **independent JVM processes**.
- The workflow uses `git hash-object` to verify the exact six source/fixture and three inherited M6 regression test blobs against this branch; **not** an entire-tree mirror or proof of current source CI admission.
- Source-derived guest code SHA-256 `bb2f7f412e60d487d02e806475a00207c32b07651261561fb47c1959788fef09` appears in the second-process log and is checked against the input `.atto` fixture.
- M10 mailbox tests: `M10_ATOMIC_MAILBOX_ACK_PROCESSED_FAILED_ABANDONED=PASS`, `M10_PANIC_CONTROL_AND_REINCARNATION_FENCE=PASS`.
- Two-process tests: `M10_TWO_REAL_JVM_PROCESSES_UDS_IPC=PASS`, `M10_RESTRICTED_COMPILED_ATTO_REMOTE_RECEIVE=PASS`, `M10_REMOTE_RECIPIENT_ADMITTED_AND_PROCESSED=PASS`, `M10_REMOTE_DUPLICATE_FAULT_AND_RESTART_FENCING=PASS`.
- Full existing M6 host regression executed at exact mirrored blobs: NIO/read EOF, 96 selected keys, external channel shutdown, CONTROL ordering, STOP→PANIC, watchdog, 100,000 producer messages, 2,000 neighbor messages, 48 stop/readiness races; `M6_NIO_SCHEDULER_SUPERVISOR_NATIVE_HOST=PASS`.
- Atto source-org Actions may fail at runner allocation (`steps:null`); a zero-step failure is neither a source compile failure nor success. Do not conflate carrier exact **blob** validation with exact **source tree** validation.

## Security and semantic limits (blocks production)

1. An HMAC authenticates protocol bytes **only relative to a trusted test-parent-provisioned secret**. The consolidated pilot checks same-UID OS peer credentials on the accepted and connected Unix sockets, but does not rotate keys, implement secure remote key agreement or support TLS. A peer who obtains this key and local socket access has protocol authority. The 0700 UDS parent, 0600 socket, same-user peer-credential check and inode-pinned unlink are defense-in-depth but insufficient for hostile-tenant isolation. Never expose this gateway as a production TenantVM listener.
2. The proof's 84-byte scalar frame is **not the normative Atto wire format**. It lacks portable schema IDs, a full capability chain and actor-specific ABI hash, and it is not interoperable with legacy Oreslang IPC v3. Migrate carefully from [legacy proxy #543](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/543), [authenticated socket bridge #1581](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1581), and [HKDF/HMAC protocol #1366](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1366).
3. The replay table is in-memory, capped at 1,024 identities and lost on process restart. The target process restart is fenced by an **independently provided fresh actor incarnation**, not by the recycled M6 generation alone. Successful admission does not establish durable execution or exactly-once effects; an IPC reset after possible admission is indeterminate.
4. The proof operates on one logical actor per gateway, opens one connection per request, and uses a **blocking test-only sender**. Production needs a shared *client-side* NIO/async pool, on-demand discovery, listener peer identities, stream multiplexing/backpressure and actual source `define proxy actor` linking, with no parking of actor carriers.
5. Do not claim a committed processing result based solely on `ADMITTED`, scheduled/started, a host snapshot, a proxy enqueue, or a durable inbox commit. A failed/abandoned result is a terminal negative stage. Supervisor CONTROL remains a distinct authenticated channel, not accessible via client DATA frames.
6. Backend and GraalVM AOT support are **NOT_EXECUTED** here. The general Atto compiler, typed request/stream guest ABI, Java bridge and each future backend need separate source-compiled test evidence.

## Next implementation gates

- Integrate the native `define proxy actor ... conforms ... discover ... transport ...` AST/type-checker from legacy #543 into the **canonical Atto Java compiler** (or fail closed until migrated); do not treat the M10 restricted compiler as a replacement.
- Replace the blocking test client with a shared nonblocking selector/async transport on the sending process. Wire proxy actor turns/typed `ActorRef` to the existing reply sidecar in [atto-spec#40](https://github.com/atto-lang/atto-spec/pull/40) and [atto-source.rs#254](https://github.com/atto-lang/atto-source.rs/pull/254). Keep exact destination mailbox admission vs completion semantics.
- Derive and verify peer/tenant/capability/transport/actor schema in a production handshake, including UDS peer credentials, mTLS for TCP, message version negotiation, per-stream byte credits and bounded retries.
- Run source-compiled positive/negative proxy syntax and receiver fixtures, process-kill/reconnect/packet-corruption tests and real compiled GraalVM Native Image, then independent backend parity evidence.
- Govern merge using [atto-lang/.github/AGENTS.md](https://github.com/atto-lang/.github/blob/main/AGENTS.md) and the P0 issue [atto-source.java#43](https://github.com/atto-lang/atto-source.java/issues/43).
