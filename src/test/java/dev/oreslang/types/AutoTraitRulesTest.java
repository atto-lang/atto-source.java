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
    void shareNeverBypassesMissingSendOrSyncProofs() {
        assertFalse(new AutoTraitRules.Capabilities(false, true, true).share());
        assertFalse(new AutoTraitRules.Capabilities(true, false, true).share());
        assertFalse(new AutoTraitRules.Capabilities(false, false, true).share());
        assertTrue(new AutoTraitRules.Capabilities(true, true, true).share());

        for (var bounds : java.util.List.of(Set.of(SHARE), Set.of(SEND, SHARE),
                Set.of(SYNC, SHARE))) {
            var caps = AutoTraitRules.infer(AutoTraitRules.TypeModel.dyn(bounds, true));
            assertFalse(caps.share(), "dyn Share must not imply Send or Sync");
        }

        var scalar = AutoTraitRules.TypeModel.scalar();
        var nominal = AutoTraitRules.TypeModel.nominal(true, scalar);
        var noSend = AutoTraitRules.infer(nominal.denying(SEND));
        assertFalse(noSend.send());
        assertFalse(noSend.share());
        var noSync = AutoTraitRules.infer(nominal.denying(SYNC));
        assertFalse(noSync.sync());
        assertFalse(noSync.share());
        assertTrue(AutoTraitRules.infer(nominal).share());
    }

    @Test
    void exhaustiveDynBoundsAndNegativeTraitsNeverGrantUnsafeShare() {
        var allTraits = new AutoTraitRules.AutoTrait[]{SEND, SYNC, SHARE};
        for (int bounds = 0; bounds < 8; bounds++) {
            var positive = java.util.EnumSet.noneOf(AutoTraitRules.AutoTrait.class);
            for (int index = 0; index < 3; index++) {
                if ((bounds & (1 << index)) != 0) positive.add(allTraits[index]);
            }
            for (int domain = 0; domain < 2; domain++) {
                for (int negatives = 0; negatives < 8; negatives++) {
                    var denied = java.util.EnumSet.noneOf(AutoTraitRules.AutoTrait.class);
                    for (int index = 0; index < 3; index++) {
                        if ((negatives & (1 << index)) != 0) denied.add(allTraits[index]);
                    }
                    var type = new AutoTraitRules.TypeModel(DYN,
                            java.util.List.of(), positive, domain != 0, denied);
                    var caps = AutoTraitRules.infer(type);
                    boolean expectedSend = (bounds & 1) != 0 && (negatives & 1) == 0;
                    boolean expectedSync = (bounds & 2) != 0 && (negatives & 2) == 0;
                    boolean expectedShare = domain != 0 && (bounds & 4) != 0
                            && (negatives & 4) == 0 && expectedSend && expectedSync;
                    assertEquals(expectedSend, caps.send());
                    assertEquals(expectedSync, caps.sync());
                    assertEquals(expectedShare, caps.share());
                }
            }
        }
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
