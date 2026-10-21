package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static dev.oreslang.types.AutoTraitRules.AutoTrait.*;
import static dev.oreslang.types.AutoTraitRules.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

final class AutoTraitRulesTest {
    @Test
    void uniqueBoxMovesButDoesNotBecomeActorSharedStorage() {
        var scalar = AutoTraitRules.TypeModel.scalar();
        var box = AutoTraitRules.TypeModel.wrapped(BOX, scalar, false);
        var caps = AutoTraitRules.infer(box);
        assertTrue(caps.send());
        assertTrue(caps.sync());
        assertFalse(caps.share());
    }

    @Test
    void arcRequiresSharedDomainAndSendSyncPayload() {
        var scalar = AutoTraitRules.TypeModel.scalar();
        var localArc = AutoTraitRules.TypeModel.wrapped(ARC, scalar, false);
        assertEquals(
                new AutoTraitRules.Capabilities(false, false, false),
                AutoTraitRules.infer(localArc));

        var sharedArc = AutoTraitRules.TypeModel.wrapped(ARC, scalar, true);
        assertEquals(
                new AutoTraitRules.Capabilities(true, true, true),
                AutoTraitRules.infer(sharedArc));

        var notSync = AutoTraitRules.TypeModel.nominal(true, scalar).denying(SYNC);
        var rejected = AutoTraitRules.TypeModel.wrapped(ARC, notSync, true);
        assertEquals(
                new AutoTraitRules.Capabilities(false, false, false),
                AutoTraitRules.infer(rejected));
    }

    @Test
    void arcDoesNotManufactureRecursiveShare() {
        var scalar = AutoTraitRules.TypeModel.scalar();
        var boxed = AutoTraitRules.TypeModel.wrapped(BOX, scalar, false);
        var caps = AutoTraitRules.infer(
                AutoTraitRules.TypeModel.wrapped(ARC, boxed, true));
        assertTrue(caps.send());
        assertTrue(caps.sync());
        assertFalse(caps.share());
    }

    @Test
    void rcAndRefCellDoNotAcquireUnsafeCrossLaneSharing() {
        var scalar = AutoTraitRules.TypeModel.scalar();
        var rc = AutoTraitRules.TypeModel.wrapped(RC, scalar, false);
        assertEquals(
                new AutoTraitRules.Capabilities(false, false, false),
                AutoTraitRules.infer(rc));

        var cell = AutoTraitRules.TypeModel.wrapped(REFCELL, scalar, false);
        assertTrue(AutoTraitRules.infer(cell).send());
        assertFalse(AutoTraitRules.infer(cell).sync());
        assertFalse(AutoTraitRules.infer(cell).share());
    }

    @Test
    void synchronizedContainersProvideSyncFromSendWithoutInventingShare() {
        var payload = AutoTraitRules.TypeModel.scalar();
        for (var kind : new AutoTraitRules.Kind[]{MUTEX, RWLOCK}) {
            var caps = AutoTraitRules.infer(
                    AutoTraitRules.TypeModel.wrapped(kind, payload, false));
            assertTrue(caps.send());
            assertTrue(caps.sync());
            assertFalse(caps.share());
        }
    }

    @Test
    void rwLockRequiresSyncPayloadForConcurrentReaders() {
        var cell = AutoTraitRules.TypeModel.wrapped(REFCELL, AutoTraitRules.TypeModel.scalar(), false);
        var mutex = AutoTraitRules.infer(AutoTraitRules.TypeModel.wrapped(MUTEX, cell, false));
        var rwlock = AutoTraitRules.infer(AutoTraitRules.TypeModel.wrapped(RWLOCK, cell, false));
        assertTrue(mutex.send());
        assertTrue(mutex.sync());
        assertTrue(rwlock.send());
        assertFalse(rwlock.sync());
        assertFalse(rwlock.share());
    }

    @Test
    void dynObjectsPreserveOnlyExplicitAutoTraitBounds() {
        var plain = AutoTraitRules.TypeModel.dyn(Set.of(), true);
        assertEquals(
                new AutoTraitRules.Capabilities(false, false, false),
                AutoTraitRules.infer(plain));

        var concurrent = AutoTraitRules.TypeModel.dyn(Set.of(SEND, SYNC), true);
        assertEquals(
                new AutoTraitRules.Capabilities(true, true, false),
                AutoTraitRules.infer(concurrent));

        var shared = AutoTraitRules.TypeModel.dyn(Set.of(SEND, SYNC, SHARE), true);
        assertEquals(
                new AutoTraitRules.Capabilities(true, true, true),
                AutoTraitRules.infer(shared));
    }

    @Test
    void nominalCapabilitiesComposeAndNegativeTraitsWin() {
        var scalar = AutoTraitRules.TypeModel.scalar();
        var nested = AutoTraitRules.TypeModel.nominal(true, scalar, scalar);
        assertEquals(
                new AutoTraitRules.Capabilities(true, true, true),
                AutoTraitRules.infer(nested));

        var local = AutoTraitRules.TypeModel.nominal(false, scalar);
        assertEquals(
                new AutoTraitRules.Capabilities(true, true, false),
                AutoTraitRules.infer(local));

        var denied = nested.denying(SEND);
        assertFalse(AutoTraitRules.infer(denied).send());
    }
}
