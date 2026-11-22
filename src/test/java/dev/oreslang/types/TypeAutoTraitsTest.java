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
        assertFalse(readonlyRecord.sync());
        assertFalse(readonlyRecord.share());
    }

    @Test
    void readonlyViewsCannotBecomeSharedThroughContainersOrReferenceCounting() {
        var view = new Types.Record(Map.of("count", Types.Primitive.INT), true);
        for (Types.Type type : List.of(
                view,
                new Types.Record(Map.of(), true),
                new Types.Tuple(List.of(view)),
                new Types.Named("Option", List.of(view)),
                new Types.Named("Result", List.of(Types.Primitive.INT, view)),
                new Types.Named("Arc", List.of(view)),
                new Types.Named("Weak", List.of(view)))) {
            var capabilities = TypeAutoTraits.inferClosed(type);
            assertFalse(capabilities.sync(), () -> "readonly view gained Sync: " + type);
            assertFalse(capabilities.share(), () -> "readonly view gained Share: " + type);
        }
    }

    @Test
    void mutableCollectionOwnershipCanMoveOrBeProtectedWithoutImplicitSharing() {
        var scalar = Types.Primitive.INT;
        for (Types.Type type : List.of(
                new Types.ListType(scalar),
                new Types.Tuple(List.of(scalar), Types.TupleKind.FIXED_ARRAY),
                new Types.Tuple(List.of(scalar), Types.TupleKind.FIXED_LIST),
                new Types.Record(Map.of("count", scalar), true))) {
            var owned = TypeAutoTraits.inferClosed(type);
            assertTrue(owned.send());
            assertFalse(owned.sync());
            assertFalse(owned.share());

            var protectedValue = TypeAutoTraits.inferClosed(
                    new Types.Named("SharedMutex", List.of(type)));
            assertTrue(protectedValue.send());
            assertTrue(protectedValue.sync());
            assertTrue(protectedValue.share());
        }
        var unsafe = new Types.ListType(
                new Types.Named("Rc", List.of(scalar)));
        assertFalse(TypeAutoTraits.inferClosed(unsafe).send());
        assertFalse(TypeAutoTraits.inferClosed(
                new Types.Named("SharedMutex", List.of(unsafe))).share());
    }

    @Test
    void writableBackingViewedReadonlyCannotMintCrossThreadSharing() {
        // A writable record may be viewed through a narrower readonly type.
        // That conversion neither freezes the allocation nor revokes the
        // original writer. Check this *variance boundary*, not just a
        // standalone readonly record literal.
        var writable = new Types.Record(Map.of("value", Types.Primitive.INT), false);
        var view = new Types.Record(Map.of("value", Types.Primitive.INT), true);
        assertTrue(Types.isAssignable(writable, view));
        assertFalse(Types.isAssignable(view, writable));
        var capabilities = TypeAutoTraits.inferClosed(view);
        assertTrue(capabilities.send(), "an exclusively owned view may still move");
        assertFalse(capabilities.sync(), "readonly assignment is not a freeze");
        assertFalse(capabilities.share(), "readonly assignment is not shared storage");

        for (Types.Type wrapped : List.of(
                new Types.Tuple(List.of(view)),
                new Types.Named("Option", List.of(view)),
                new Types.Named("Result", List.of(Types.Primitive.INT, view)),
                new Types.Named("Arc", List.of(view)),
                new Types.Named("Weak", List.of(view)))) {
            var nested = TypeAutoTraits.inferClosed(wrapped);
            assertFalse(nested.sync(), () -> "wrapper minted Sync: " + wrapped);
            assertFalse(nested.share(), () -> "wrapper minted Share: " + wrapped);
        }
        assertFalse(ThreadSafetyTraits.has(
                new Types.Borrow(view, false),
                ThreadSafetyTraits.Trait.SEND,
                (named, trait) -> false),
                "a borrowed readonly alias must not be Send across threads");
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
}

