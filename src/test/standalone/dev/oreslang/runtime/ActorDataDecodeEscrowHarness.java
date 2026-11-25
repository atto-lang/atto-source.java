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
    private static void deny(Runnable action, String name) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(name);
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
        // Exercise descriptor-derived nested shape using the actual wire
        // encoder and decoder, not a text parser or fake materializer.
        var shape = new Sequence(SequenceKind.LIST,
                new Sequence(SequenceKind.LIST, new Scalar(ScalarKind.I64), -1), -1);
        Descriptor nestedPin = ActorDataDescriptorFixture.shape(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2, shape);
        var plan = new ActorDataMessagePackPlanV1(nestedPin);
        byte[] nestedWire = MessagePackWireV2.encode(nestedPin.schemaId(),
                MessagePackWireV2.Shape.list(MessagePackWireV2.Shape.list(MessagePackWireV2.Shape.i64())),
                java.util.List.of(java.util.List.of(1L, 2L), java.util.List.of(3L)));
        long nestedBytes = ActorDataMessagePackPlanV1.logicalBytes(nestedWire.length);
        long nestedWork = ActorDataMessagePackPlanV1.logicalWork(nestedWire.length);
        check(java.util.List.of(java.util.List.of(1L, 2L), java.util.List.of(3L)).equals(
                plan.decodeOwned(nestedPin.format(), nestedPin.schemaId(), 1, nestedPin.fingerprintHex(),
                        nestedWire, nestedBytes, nestedWork)), "descriptor-derived nested wire");
        deny(() -> plan.decodeOwned(nestedPin.format(), nestedPin.schemaId(), 1, nestedPin.fingerprintHex(),
                nestedWire, nestedBytes - 1, nestedWork), "nested one byte under budget");
        deny(() -> plan.decodeOwned(nestedPin.format(), nestedPin.schemaId(), 1, nestedPin.fingerprintHex(),
                nestedWire, nestedBytes, nestedWork - 1), "nested one work under budget");
        deny(() -> plan.decodeOwned(nestedPin.format(), nestedPin.schemaId(), 1, pin.fingerprintHex(),
                nestedWire, nestedBytes, nestedWork), "wrong descriptor fingerprint");
        deny(() -> plan.decodeOwned(nestedPin.format(), nestedPin.schemaId(), 1, nestedPin.fingerprintHex(),
                wire, nestedBytes, nestedWork), "wrong wire shape hash");
        var fixed = ActorDataDescriptorFixture.shape(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2,
                new Sequence(SequenceKind.FIXED_ARRAY, new Scalar(ScalarKind.I64), 2));
        deny(() -> new ActorDataMessagePackPlanV1(fixed), "unverified fixed guest representation");
        var utf = ActorDataDescriptorFixture.shape(Purpose.ACTOR_DATA, Format.MESSAGEPACK_V2,
                new Scalar(ScalarKind.UTF8));
        byte[] utfWire = MessagePackWireV2.encode(utf.schemaId(), MessagePackWireV2.Shape.string(), "Perú");
        check("Perú".equals(new ActorDataMessagePackPlanV1(utf).decodeOwned(utf.format(), utf.schemaId(),
                1, utf.fingerprintHex(), utfWire, ActorDataMessagePackPlanV1.logicalBytes(utfWire.length),
                ActorDataMessagePackPlanV1.logicalWork(utfWire.length))), "descriptor UTF8");
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
