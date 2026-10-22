package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.UUID;

/** Standalone model-scope safety checks; not guest actor/transport conformance. */
public final class GenServerReplyLedgerGenerationalSmoke {
    private static void yes(boolean b) {
        if (!b) throw new AssertionError("generational reply slot invariant failed");
    }

    public static void main(String[] args) throws Exception {
        var id = UUID.randomUUID();
        var actor = new GenServerReplyLedger.Target(UUID.randomUUID(), id, 1);
        var restart = new GenServerReplyLedger.Target(actor.runtimeId(), id, 2);
        var foreignRuntime = new GenServerReplyLedger.Target(UUID.randomUUID(), id, 1);
        var ledger = new GenServerReplyLedger(1);
        var old = ledger.register(actor);
        yes(ledger.retire(old));
        var newest = ledger.register(actor);
        yes(old.slotIndex() == newest.slotIndex());
        yes(old.slotGeneration() != newest.slotGeneration());
        yes(!ledger.tryReply(old, actor));
        yes(!ledger.tryReply(newest, restart));
        yes(!ledger.tryReply(newest, foreignRuntime));
        yes(ledger.tryReply(newest, actor));
        yes(!ledger.retire(newest));
        for (int i = 0; i < 10_000; i++) {
            var t = ledger.register(actor);
            yes(t.slotIndex() == 0);
            yes(!ledger.tryReply(old, actor));
            yes(ledger.retire(t));
        }
        yes(ledger.pendingCount() == 0);

        // Simulate near-wrap state, otherwise this boundary needs 2^32 calls.
        var exhausted = new GenServerReplyLedger(1);
        var initial = exhausted.register(actor);
        yes(exhausted.retire(initial));
        Field slotsField = GenServerReplyLedger.class.getDeclaredField("slots");
        slotsField.setAccessible(true);
        var slots = (ArrayList<?>) slotsField.get(exhausted);
        Object slot = slots.get(0);
        Field generationField = slot.getClass().getDeclaredField("generation");
        generationField.setAccessible(true);
        generationField.setLong(slot, 0xffff_ffffL);
        var last = exhausted.register(actor);
        yes(last.slotGeneration() == 0xffff_ffffL);
        yes(exhausted.tryReply(last, actor));
        yes(exhausted.pendingCount() == 0);
        try {
            exhausted.register(actor);
            throw new AssertionError("must not wrap a used slot generation");
        } catch (IllegalStateException expected) {
            yes(expected.getMessage().contains("retired"));
        }
        yes(exhausted.close() == 0);
        yes(!exhausted.tryReply(last, actor));
        System.out.println("GENSERVER_GENERATIONAL_ABA_CHURN_EXHAUSTION_PASS");
    }
}
