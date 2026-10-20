package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypeAutoTraitsTest {
    @Test
    void smartPointerCapabilitiesFollowCanonicalRules() {
        var integer = Types.Primitive.INT;
        var box = new Types.Named("Box", List.of(integer));
        var boxCaps = TypeAutoTraits.inferClosed(box);
        assertTrue(boxCaps.send());
        assertTrue(boxCaps.sync());
        assertFalse(boxCaps.share());

        var arcBox = TypeAutoTraits.inferClosed(
                new Types.Named("Arc", List.of(box)));
        assertTrue(arcBox.send());
        assertTrue(arcBox.sync());
        assertFalse(arcBox.share());

        var arcInt = TypeAutoTraits.inferClosed(
                new Types.Named("Arc", List.of(integer)));
        assertTrue(arcInt.send());
        assertTrue(arcInt.sync());
        assertTrue(arcInt.share());

        var rc = TypeAutoTraits.inferClosed(
                new Types.Named("Rc", List.of(integer)));
        assertFalse(rc.send());
        assertFalse(rc.sync());
        assertFalse(rc.share());
    }

    @Test
    void interiorMutabilityAndLocksDoNotInventSync() {
        var integer = Types.Primitive.INT;
        var refCell = new Types.Named("RefCell", List.of(integer));
        var refCaps = TypeAutoTraits.inferClosed(refCell);
        assertTrue(refCaps.send());
        assertFalse(refCaps.sync());
        assertFalse(refCaps.share());

        var rwLock = TypeAutoTraits.inferClosed(
                new Types.Named("RwLock", List.of(refCell)));
        assertTrue(rwLock.send());
        assertFalse(rwLock.sync());
        assertFalse(rwLock.share());

        var sharedMutex = TypeAutoTraits.inferClosed(
                new Types.Named("SharedMutex", List.of(refCell)));
        assertTrue(sharedMutex.send());
        assertTrue(sharedMutex.sync());
        assertTrue(sharedMutex.share());
    }

    @Test
    void mutableContainersAreSendButNotImplicitlySyncOrShare() {
        var integer = Types.Primitive.INT;

        var list = TypeAutoTraits.inferClosed(new Types.ListType(integer));
        assertTrue(list.send());
        assertFalse(list.sync());
        assertFalse(list.share());

        var mutableRecord = TypeAutoTraits.inferClosed(
                new Types.Record(Map.of("x", integer), false));
        assertTrue(mutableRecord.send());
        assertFalse(mutableRecord.sync());
        assertFalse(mutableRecord.share());

        var readonlyRecord = TypeAutoTraits.inferClosed(
                new Types.Record(Map.of("x", integer), true));
        assertTrue(readonlyRecord.send());
        assertTrue(readonlyRecord.sync());
        assertTrue(readonlyRecord.share());
    }

    @Test
    void actorRefIsAnExplicitShareableCapability() {
        var ref = TypeAutoTraits.inferClosed(
                new Types.Named(
                        "ActorRef",
                        List.of(new Types.Named("Worker", List.of()))));
        assertTrue(ref.send());
        assertTrue(ref.sync());
        assertTrue(ref.share());
    }
    @Test
    void immutableEntryComposesBothComponentCapabilities() {
        var integer = Types.Primitive.INT;
        var string = Types.Primitive.STRING;

        var plain = TypeAutoTraits.inferClosed(
                new Types.Named("Entry", List.of(integer, string)));
        assertTrue(plain.send());
        assertTrue(plain.sync());
        assertTrue(plain.share());

        var boxed = TypeAutoTraits.inferClosed(
                new Types.Named(
                        "Entry",
                        List.of(
                                integer,
                                new Types.Named("Box", List.of(integer)))));
        assertTrue(boxed.send());
        assertTrue(boxed.sync());
        assertFalse(boxed.share());

        var rc = TypeAutoTraits.inferClosed(
                new Types.Named(
                        "Entry",
                        List.of(
                                integer,
                                new Types.Named("Rc", List.of(integer)))));
        assertFalse(rc.send());
        assertFalse(rc.sync());
        assertFalse(rc.share());
    }

}
