package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.SendSyncTraits;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types.*;
import dev.oreslang.types.Types.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SendSyncTraitsTest {
    private static Named named(String name, Type... args) {
        return new Named(name, List.of(args));
    }

    private static final SendSyncTraits.NominalProof NO_NOMINAL =
            (type, trait) -> false;

    @Test
    void ownershipMoveAndSharedBorrowAreSeparateTraits() {
        var ints = new ListType(Primitive.INT);
        var record = new Record(Map.of("hp", Primitive.INT, "name", Primitive.STRING));
        for (Type type : List.of(Primitive.INT, ints, record,
                new Tuple(List.of(Primitive.BOOL, ints)))) {
            assertTrue(SendSyncTraits.isSend(type, NO_NOMINAL), type.toString());
            assertTrue(SendSyncTraits.isSync(type, NO_NOMINAL), type.toString());
        }
        assertFalse(SendSyncTraits.isSend(Unknown.INSTANCE, NO_NOMINAL));
        assertTrue(SendSyncTraits.isOwnedSend(ints, NO_NOMINAL));
        assertFalse(SendSyncTraits.isOwnedSend(
                new Borrow(Primitive.INT, false), NO_NOMINAL));
        assertFalse(SendSyncTraits.isOwnedSend(
                new Tuple(List.of(Primitive.INT, new Borrow(Primitive.INT, false))),
                NO_NOMINAL));
        assertFalse(SendSyncTraits.isOwnedSend(
                named("Option", new Borrow(Primitive.INT, true)), NO_NOMINAL));
        assertFalse(SendSyncTraits.isOwnedSend(
                new Record(Map.of("loan", new Borrow(Primitive.INT, false))),
                NO_NOMINAL));
        assertFalse(SendSyncTraits.isSync(named("UnverifiedClass"), NO_NOMINAL));
    }

    @Test
    void mutableCellCanBeMovedButNotSharedWithoutSynchronization() {
        Type cell = named("Cell", Primitive.INT);
        assertTrue(SendSyncTraits.isSend(cell, NO_NOMINAL));
        assertFalse(SendSyncTraits.isSync(cell, NO_NOMINAL));
        // &Cell<T> cannot be transferred when Cell<T> is not Sync.
        assertFalse(SendSyncTraits.isSend(new Borrow(cell, false), NO_NOMINAL));
        // &mut Cell<T> can be transferred under a proven exclusive lifetime.
        assertTrue(SendSyncTraits.isSend(new Borrow(cell, true), NO_NOMINAL));
        assertFalse(SendSyncTraits.isSync(new Borrow(cell, true), NO_NOMINAL));
    }

    @Test
    void arcMutexAndRwLockRespectDifferentInnerBounds() {
        Type cell = named("Cell", Primitive.INT);
        assertFalse(SendSyncTraits.isSend(named("Arc", cell), NO_NOMINAL));
        assertFalse(SendSyncTraits.isSync(named("Arc", cell), NO_NOMINAL));
        assertTrue(SendSyncTraits.isSend(named("Mutex", cell), NO_NOMINAL));
        assertTrue(SendSyncTraits.isSync(named("Mutex", cell), NO_NOMINAL));
        // Movable RwLock<Cell<int>> is safe, even though sharing readers
        // would be unsafe because Cell<int> has interior mutation.
        assertTrue(SendSyncTraits.isSend(named("RwLock", cell), NO_NOMINAL));
        assertFalse(SendSyncTraits.isSync(named("RwLock", cell), NO_NOMINAL));
        assertTrue(SendSyncTraits.isSync(named("Arc",
                named("Mutex", cell)), NO_NOMINAL));
        assertTrue(SendSyncTraits.isSync(named("RwLock", Primitive.INT), NO_NOMINAL));
    }

    @Test
    void unknownAndThreadAffineCapabilitiesFailClosed() {
        for (Type type : List.of(named("Rc", Primitive.INT),
                named("ThreadLocal", Primitive.INT),
                named("NativePointer", Primitive.INT),
                named("MutexGuard", Primitive.INT),
                new Function(List.of(Primitive.INT), Primitive.INT))) {
            assertFalse(SendSyncTraits.isSend(type, NO_NOMINAL), type.toString());
            assertFalse(SendSyncTraits.isSync(type, NO_NOMINAL), type.toString());
        }
        SendSyncTraits.NominalProof movedOnly =
                (type, trait) -> type.name().equals("OwnedPlayer")
                        && trait == SendSyncTraits.Trait.SEND;
        assertTrue(SendSyncTraits.isSend(named("OwnedPlayer"), movedOnly));
        assertFalse(SendSyncTraits.isSync(named("OwnedPlayer"), movedOnly));
    }

    @Test
    void existingGuestAsyncBoundariesNowUseSendAdmission() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                    pub let int count = 0;
                end
                async fnc run(Box owned): int {
                    return owned.count;
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc read(Channel<Option<List<int>>> input): int {
                    return 1;
                }
                """)));
        var future = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    async fnc bad(Future<int> pending): int {
                        return 1;
                    }
                    """)));
        assertTrue(future.getMessage().contains("owned Send"), future.getMessage());
        var guard = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                    async fnc bad(Mutex<int> lock): int {
                        return 1;
                    }
                    """)));
        // The type may be Send structurally, but the mutex guest source
        // lowering is still disallowed at the async boundary.
        assertTrue(guard.getMessage().contains("task-safe"), guard.getMessage());
    }
}
