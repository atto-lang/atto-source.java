package dev.oreslang.runtime;

import dev.oreslang.types.ActorDataDescriptorFixture;
import dev.oreslang.types.PortableCodecRegistry.*;
import java.util.concurrent.*;

public final class ActorDataDecodeEscrowHarness {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Descriptor pin = ActorDataDescriptorFixture.scalar(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2);
        byte[] wire = MessagePackWireV2.encode(pin.schemaId(), MessagePackWireV2.Shape.i64(), 42L);
        long bytes = 256L + 16L * wire.length, work = 3L * wire.length + 1L;
        var ledger = new ActorDataDecodeEscrowV1(pin, 7, bytes, work, 1);
        check(ledger.reserve(7, Format.PROTOBUF, pin.schemaId(), 1, pin.fingerprintHex(), wire.length) == null, "codec");
        check(ledger.reserve(7, pin.format(), "app.Wrong", 1, pin.fingerprintHex(), wire.length) == null, "schema");
        check(ledger.reserve(7, pin.format(), pin.schemaId(), 2, pin.fingerprintHex(), wire.length) == null, "version");
        check(ledger.reserve(7, pin.format(), pin.schemaId(), 1, "wrong", wire.length) == null, "fingerprint");
        check(ledger.reserve(7, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length + 1) == null, "budget");
        var ticket = ledger.reserve(7, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
        check(ticket != null && ledger.snapshot().availableBytes() == 0, "exact reserve");
        check(ledger.decodeAndCommit(ticket, wire), "real wire decode and commit");
        ledger.cancel(ticket);
        check(ledger.snapshot().queued() == 1 && ledger.snapshot().availableBytes() == 0, "late cancel");
        check(!ledger.decodeAndCommit(ticket, wire), "ticket replay");
        check(ledger.dequeue(7) == 42L && ledger.dequeue(7) == null, "dequeue once");
        check(ledger.snapshot().availableBytes() == bytes && ledger.snapshot().availableWork() == work, "credits restored");
        var bad = ledger.reserve(7, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
        byte[] malformed = wire.clone(); malformed[0] = 0;
        check(!ledger.decodeAndCommit(bad, malformed), "malformed no publication");
        ledger.cancel(bad);
        check(ledger.snapshot().availableBytes() == bytes && ledger.snapshot().publications() == 1, "rollback once");
        var stale = ledger.reserve(7, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
        ledger.restart(8);
        check(!ledger.decodeAndCommit(stale, wire) && ledger.snapshot().availableBytes() == bytes, "stale ticket");
        check(ledger.reserve(7, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length) == null, "stale epoch");
        var other = new ActorDataDecodeEscrowV1(pin, 8, bytes, work, 1);
        var current = ledger.reserve(8, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
        check(!other.decodeAndCommit(current, wire), "foreign ticket");
        ledger.cancel(current); ledger.cancel(current);
        check(ledger.snapshot().availableBytes() == bytes, "cancel replay");
        // Independent endpoint tickets compete for one shared runtime budget.
        var aggregate = new ActorDataDecodeEscrowV1.RuntimeBudget(bytes);
        var first = aggregate.reserve(wire.length);
        check(first != null && aggregate.inFlight() == 1, "runtime exact reserve");
        check(aggregate.reserve(wire.length) == null, "runtime aggregate denial");
        first.close(); first.close();
        check(aggregate.inFlight() == 0, "runtime lease released once");
        var second = aggregate.reserve(wire.length);
        check(second != null, "runtime recovered"); second.close();
        // Deterministic active-decode ownership seam: cancellation/close after
        // decoder acquisition but before mailbox settlement cannot refund.
        var closing = new ActorDataDecodeEscrowV1(pin, 8, bytes, work, 1);
        var active = closing.reserve(8, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
        check(Long.valueOf(42).equals(closing.decodeForMailbox(active, wire)), "decode for mailbox");
        closing.closeEndpoint();
        check(closing.snapshot().availableBytes() == 0, "close retains active decode");
        check(closing.reserve(8, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length) == null, "closed endpoint denial");
        closing.finishMailboxDecode(active); closing.finishMailboxDecode(active);
        check(closing.snapshot().availableBytes() == bytes, "active settlement once");
        // Race commit vs cancellation: either linearization is acceptable.
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                var t = ledger.reserve(8, pin.format(), pin.schemaId(), 1, pin.fingerprintHex(), wire.length);
                check(t != null, "race reserve");
                var gate = new CountDownLatch(1);
                var commit = pool.submit(() -> { gate.await(); return ledger.decodeAndCommit(t, wire); });
                var cancel = pool.submit(() -> { gate.await(); ledger.cancel(t); return null; });
                gate.countDown();
                boolean accepted = commit.get(5, TimeUnit.SECONDS);
                cancel.get(5, TimeUnit.SECONDS);
                Long result = ledger.dequeue(8);
                check(accepted ? Long.valueOf(42).equals(result) : result == null, "race publication");
                check(ledger.snapshot().availableBytes() == bytes && ledger.snapshot().availableWork() == work,
                        "race credit conservation");
            }
        }
        System.out.println("actor DATA escrow standalone checks passed: " + checks);
    }
}
