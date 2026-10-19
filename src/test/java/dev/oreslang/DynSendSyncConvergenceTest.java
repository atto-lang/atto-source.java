package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ThreadSafetyTraits;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class DynSendSyncConvergenceTest {
    private static final ThreadSafetyTraits.NominalProof PERMISSIVE = (type, trait) -> true;
    private static final ThreadSafetyTraits.NominalProof NO_NOMINAL = (type, trait) -> false;

    @Test
    void erasedDynCannotInheritSendOrSyncFromNominalInterfaceName() {
        Type view = new Types.Dyn(new Types.Named("Worker", List.of()));
        for (Trait trait : Trait.values()) {
            assertFalse(ThreadSafetyTraits.has(view, trait, PERMISSIVE));
            assertFalse(ThreadSafetyTraits.has(new Types.Borrow(view, false), trait, PERMISSIVE));
            assertFalse(ThreadSafetyTraits.has(new Types.Borrow(view, true), trait, PERMISSIVE));
        }
    }

    @Test
    void nestedErasedDynCannotGainThreadTraits() {
        Type view = new Types.Dyn(new Types.Named("Worker", List.of()));
        List<Type> nested = List.of(
                new Types.Named("Option", List.of(view)),
                new Types.Named("Result", List.of(Types.Primitive.INT, view)),
                new Types.ListType(view),
                new Types.Tuple(List.of(Types.Primitive.INT, view)),
                new Types.Record(Map.of("worker", view), true),
                new Types.Union(List.of(view, Types.Primitive.INT)),
                new Types.Named("UserWrapper", List.of(view)));
        for (Type type : nested) {
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(type, trait, PERMISSIVE),
                        "dyn escaped thread trait rejection inside " + type);
            }
        }
    }

    @Test
    void unloweredArcWeakAndLockNamesCannotForgeIntrinsicTraits() {
        for (String name : List.of(
                "Arc", "Weak", "Rc", "RcWeak", "Cell", "RefCell",
                "Mutex", "RwLock", "SharedMutex", "MutexGuard", "ReadGuard",
                "WriteGuard", "RawPointer", "NativePointer", "ActorRef",
                "ExecutionPermit", "ThreadLocal")) {
            Type alleged = new Types.Named(name, List.of(Types.Primitive.INT));
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(alleged, trait, PERMISSIVE),
                        "nominal callback manufactured runtime guarantee for " + name);
            }
        }
    }

    @Test
    void ordinaryImmutableAndMovableValuesKeepTheirProvenTraits() {
        Type immutable = new Types.Record(
                Map.of("count", Types.Primitive.INT), true);
        Type mutable = new Types.ListType(Types.Primitive.INT);
        for (Trait trait : Trait.values()) {
            assertTrue(ThreadSafetyTraits.has(immutable, trait, NO_NOMINAL));
            assertTrue(ThreadSafetyTraits.has(new Types.Tuple(List.of(
                    Types.Primitive.INT, Types.Primitive.STRING)), trait, NO_NOMINAL));
        }
        assertTrue(ThreadSafetyTraits.has(mutable, Trait.SEND, NO_NOMINAL));
        assertFalse(ThreadSafetyTraits.has(mutable, Trait.SYNC, NO_NOMINAL));
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(mutable, false),
                Trait.SEND, NO_NOMINAL));
    }

    @Test
    void asyncCallBoundaryRejectsErasedDynEvenWithValidNominalImplementation() {
        String program = """
                define interface Worker as fnc run(): int; end
                define class RealWorker implements Worker as
                    pub run(): int { return 1; }
                end
                async fnc runOnTask(dyn Worker worker): int {
                    return worker.run();
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void asyncOwnedChannelRetainsExistingSendAdmission() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc receiveNext(Channel<Option<List<int>>> inbox): int {
                    val Option<List<int>> x = await (nb readch inbox);
                    if x.is_none() then return 0; fi
                    return x.unwrap().length;
                }
                """)));
    }

    @Test
    void spoofedArcNominalCannotEnterAsyncTaskBoundary() {
        String program = """
                define class Arc as end
                async fnc launch(Arc handle): int {
                    return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void unresolvedAndDeepTypesNeverGainSendOrSync() {
        Type nested = new Types.Dyn(new Types.Named("Worker", List.of()));
        for (int i = 0; i < 400; i++) nested = new Types.ListType(nested);
        for (Trait trait : Trait.values()) {
            assertFalse(ThreadSafetyTraits.has(nested, trait, PERMISSIVE));
            assertFalse(ThreadSafetyTraits.has(Types.Unknown.INSTANCE, trait, PERMISSIVE));
            assertFalse(ThreadSafetyTraits.has(new Types.Generic("T"), trait, PERMISSIVE));
        }
        assertTrue(ThreadSafetyTraits.has(
                new Types.ListType(Types.Primitive.INT), Trait.SEND, NO_NOMINAL));
    }
}
