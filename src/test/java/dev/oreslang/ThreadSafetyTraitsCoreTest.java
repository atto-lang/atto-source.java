package dev.oreslang;

import dev.oreslang.types.ThreadSafetyTraits;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ThreadSafetyTraitsCoreTest {
    private static final ThreadSafetyTraits.NominalProof NO_NOMINALS =
            (type, trait) -> false;

    private static boolean has(Type type, Trait trait) {
        return ThreadSafetyTraits.has(type, trait, NO_NOMINALS);
    }

    @Test
    void derivesOnlyConservativeStructuralSendSync() {
        for (Trait trait : Trait.values()) {
            assertTrue(has(Types.Primitive.INT, trait));
            assertTrue(has(Types.Primitive.STRING, trait));
            assertTrue(has(new Types.BoolLiteral(true), trait));
            assertTrue(has(new Types.Named("ActorId", List.of()), trait));
            assertTrue(has(new Types.Named("ActorKind", List.of()), trait));
            assertFalse(has(Types.Primitive.VOID, trait));
        }

        Type list = new Types.ListType(Types.Primitive.INT);
        Type record = new Types.Record(Map.of("value", Types.Primitive.INT));
        Type readonlyView =
                new Types.Record(Map.of("value", Types.Primitive.INT), true);
        Type fixedArray = new Types.Tuple(
                List.of(Types.Primitive.INT), Types.TupleKind.FIXED_ARRAY);

        for (Type mutable : List.of(list, record, readonlyView, fixedArray)) {
            assertTrue(has(mutable, Trait.SEND));
            assertFalse(has(mutable, Trait.SYNC));
        }
        assertTrue(has(
                new Types.Tuple(List.of(Types.Primitive.INT, Types.Primitive.STRING)),
                Trait.SYNC));
    }

    @Test
    void borrowRulesDistinguishMoveFromSharedAccess() {
        Type sendOnly = new Types.Named("SendOnly", List.of());
        ThreadSafetyTraits.NominalProof proof =
                (type, trait) -> type.name().equals("SendOnly")
                        && trait == Trait.SEND;

        assertTrue(ThreadSafetyTraits.has(sendOnly, Trait.SEND, proof));
        assertFalse(ThreadSafetyTraits.has(sendOnly, Trait.SYNC, proof));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Borrow(sendOnly, false), Trait.SEND, proof));
        assertTrue(ThreadSafetyTraits.has(
                new Types.Borrow(sendOnly, true), Trait.SEND, proof));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Borrow(sendOnly, true), Trait.SYNC, proof));
        assertTrue(ThreadSafetyTraits.has(
                new Types.Borrow(Types.Primitive.INT, false), Trait.SEND, proof));
    }

    @Test
    void capabilityTypesAndUnknownsFailClosed() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (String name : List.of(
                "ActorRef", "SharedMutex", "MutexGuard", "Future",
                "ExecutionPermit", "ReadLoan", "WriteLoan", "PartitionLoan")) {
            Type capability = new Types.Named(name, List.of(Types.Primitive.INT));
            assertFalse(ThreadSafetyTraits.has(capability, Trait.SEND, permissive));
            assertFalse(ThreadSafetyTraits.has(capability, Trait.SYNC, permissive));
        }
        assertFalse(ThreadSafetyTraits.has(
                Types.Unknown.INSTANCE, Trait.SEND, permissive));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Generic("T"), Trait.SEND, permissive));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Named("Channel", List.of(Types.Unknown.INSTANCE)),
                Trait.SEND, permissive));
    }

    @Test
    void nominalProofCannotSkipUnsafeGenericArgumentsOrReservedArities() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        Type unsafe = new Types.Named(
                "Wrapper", List.of(new Types.Named(
                        "Future", List.of(Types.Primitive.INT))));
        assertFalse(ThreadSafetyTraits.has(unsafe, Trait.SEND, permissive));

        for (Trait trait : Trait.values()) {
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("ActorId", List.of(Types.Primitive.INT)),
                    trait, permissive));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("Option", List.of()), trait, permissive));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("Result", List.of(Types.Primitive.INT)),
                    trait, permissive));
        }
    }

    @Test
    void malformedMetadataIsRejectedBeforeNominalCallback() {
        AtomicInteger calls = new AtomicInteger();
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> {
            calls.incrementAndGet();
            return true;
        };
        List<Type> malformed = List.of(
                new Types.Named("", List.of()),
                new Types.Named("   ", List.of()),
                new Types.Named(null, List.of()),
                new Types.Generic(null),
                new Types.Generic("   "),
                new Types.StringLiteral(null),
                new Types.ListType(null),
                new Types.Borrow(null, false),
                new Types.ClassNamespace(null),
                new Types.ClassNamespace("   "),
                new Types.Function(List.of(Types.Primitive.INT), (Type) null),
                new Types.Record(Map.of("", Types.Primitive.INT)));

        for (Type type : malformed) {
            assertFalse(ThreadSafetyTraits.has(type, Trait.SEND, permissive),
                    () -> "malformed metadata admitted: " + type);
        }
        assertEquals(0, calls.get());
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void proofWorkIsBoundedForDepthWidthAndSharedDags() {
        Type tooDeep = Types.Primitive.INT;
        for (int i = 0; i < 8192; i++) {
            tooDeep = new Types.ListType(tooDeep);
        }
        assertFalse(has(tooDeep, Trait.SEND));

        Type wide = new Types.Tuple(
                java.util.Collections.nCopies(100_001, Types.Primitive.INT));
        assertFalse(has(wide, Trait.SEND));

        AtomicInteger nominalCalls = new AtomicInteger();
        ThreadSafetyTraits.NominalProof safeLeaf = (type, trait) -> {
            nominalCalls.incrementAndGet();
            return type.name().equals("KnownSafe");
        };
        Type dag = new Types.Named("KnownSafe", List.of());
        for (int i = 0; i < 70; i++) {
            dag = new Types.Tuple(List.of(dag, dag));
        }
        assertTrue(ThreadSafetyTraits.has(dag, Trait.SEND, safeLeaf));
        assertTrue(ThreadSafetyTraits.has(dag, Trait.SYNC, safeLeaf));
        assertEquals(2, nominalCalls.get());

        assertTrue(has(Types.Primitive.INT, Trait.SEND),
                "a failed bounded proof must not poison the next query");
    }

    @Test
    void channelSendProofDoesNotInventChannelSync() {
        Type channel = new Types.Named(
                "Channel", List.of(Types.Primitive.INT));
        assertTrue(has(channel, Trait.SEND));
        assertFalse(has(channel, Trait.SYNC));
        assertFalse(has(new Types.Named("Channel", List.of()), Trait.SEND));
        assertFalse(has(new Types.Named(
                "Channel", List.of(new Types.Named(
                        "MutexGuard", List.of(Types.Primitive.INT)))),
                Trait.SEND));
    }
    @Test
    void unloweredSharedStateAndTaskCapabilitiesCannotBeBlessedByNominalCallback() {
        // The nominal callback is compiler-trusted, but may be overly broad.
        // Unsupported runtime/allocator/thread-affinity handles must deny
        // regardless of any nested generic argument being harmless.
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (String name : List.of(
                "Arc", "Weak", "Rc", "RefCell", "Cell", "RwLock",
                "RwLockReadGuard", "RwLockWriteGuard",
                "Ref", "TRef", "EtsTable", "TableId",
                "Task", "TaskHandle", "TaskScope", "TaskCancellation",
                "ThreadBound", "NativeHandle", "Pointer", "RawPointer")) {
            Type capability = new Types.Named(name, List.of(Types.Primitive.INT));
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(capability, trait, permissive),
                        () -> name + " cannot derive " + trait + " before ABI proof");
                assertFalse(ThreadSafetyTraits.has(
                        new Types.Named("Option", List.of(capability)),
                        trait, permissive),
                        () -> "Option cannot launder " + name + " capability");
            }
        }
        assertTrue(ThreadSafetyTraits.has(
                new Types.Named("Option", List.of(Types.Primitive.INT)),
                Trait.SEND, permissive));
    }

}
