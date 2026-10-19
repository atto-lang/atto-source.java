package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ThreadSafetyTraits;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ThreadSafetyTraitsTest {
    private static final ThreadSafetyTraits.NominalProof NO_NOMINALS = (type, trait) -> false;

    private static boolean has(Type type, Trait trait) {
        return ThreadSafetyTraits.has(type, trait, NO_NOMINALS);
    }

    @Test
    void scalarsAndImmutableValuesDeriveBothTraitsTransitively() {
        Type frozen = new Types.Record(Map.of(
                "pos", new Types.Tuple(List.of(Types.Primitive.FLOAT, Types.Primitive.FLOAT)),
                "hp", Types.Primitive.INT), true);
        for (Trait trait : Trait.values()) {
            assertTrue(has(Types.Primitive.INT, trait));
            assertTrue(has(Types.Primitive.STRING, trait));
            // The view's readOnly capability is not deep-freeze evidence.
            boolean expected = trait == Trait.SEND;
            assertEquals(expected, has(frozen, trait));
            assertEquals(expected, has(new Types.Named("Option", List.of(frozen)), trait));
            assertEquals(expected,
                    has(new Types.Named("Result", List.of(frozen, Types.Primitive.STRING)), trait));
            assertTrue(has(new Types.Union(List.of(Types.Primitive.INT, Types.Primitive.STRING)), trait));
            assertFalse(has(Types.Primitive.VOID, trait));
        }
    }

    @Test
    void writableContainersAreSendButNotSyncEvenForSyncElements() {
        Type list = new Types.ListType(Types.Primitive.INT);
        Type record = new Types.Record(Map.of("hp", Types.Primitive.INT));
        Type fixedArray = new Types.Tuple(
                List.of(Types.Primitive.INT, Types.Primitive.INT),
                Types.TupleKind.FIXED_ARRAY);
        Type fixedList = new Types.Tuple(
                List.of(Types.Primitive.INT), Types.TupleKind.FIXED_LIST);
        for (Type mutable : List.of(list, record, fixedArray, fixedList)) {
            assertTrue(has(mutable, Trait.SEND), "owned aggregate can move: " + mutable);
            assertFalse(has(mutable, Trait.SYNC), "mutable aggregate cannot be shared: " + mutable);
            assertFalse(has(new Types.Borrow(mutable, false), Trait.SEND),
                    "non-Sync borrowed aggregate cannot cross threads: " + mutable);
        }
        Type readonlyWithMutableNestedField = new Types.Record(Map.of("values", list), true);
        assertTrue(has(readonlyWithMutableNestedField, Trait.SEND));
        assertFalse(has(readonlyWithMutableNestedField, Trait.SYNC));
        assertFalse(has(new Types.Named("Option", List.of(list)), Trait.SYNC));
        assertFalse(has(new Types.Named("Result", List.of(record, Types.Primitive.INT)), Trait.SYNC));
        assertFalse(has(new Types.Union(List.of(list, Types.Primitive.INT)), Trait.SYNC));

        // Even an authorized nominal Sync proof for an element cannot turn
        // the enclosing mutable collection into Sync.
        Type syncOnly = new Types.Named("SyncOnly", List.of());
        ThreadSafetyTraits.NominalProof proof =
                (type, trait) -> type.name().equals("SyncOnly") && trait == Trait.SYNC;
        assertFalse(ThreadSafetyTraits.has(new Types.ListType(syncOnly), Trait.SYNC, proof));
        assertFalse(ThreadSafetyTraits.has(new Types.Record(Map.of("x", syncOnly)), Trait.SYNC, proof));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Record(Map.of("x", syncOnly), true), Trait.SYNC, proof));
    }

    @Test
    void readonlyViewIsNotADeepFreezeOrAliasExclusivityProof() {
        Type mutableOwner = new Types.Record(Map.of("counter", Types.Primitive.INT));
        // The checker can overlay readOnly onto a view without revoking the
        // mutable owner's authority; sharing that view across threads is unsafe.
        Type readonlyView = new Types.Record(Map.of("counter", Types.Primitive.INT), true);
        assertTrue(has(mutableOwner, Trait.SEND));
        assertTrue(has(readonlyView, Trait.SEND));
        assertFalse(has(mutableOwner, Trait.SYNC));
        assertFalse(has(readonlyView, Trait.SYNC));
        assertFalse(has(new Types.Borrow(readonlyView, false), Trait.SEND));
        assertFalse(has(new Types.Borrow(readonlyView, true), Trait.SYNC));
        for (Type wrapper : List.of(
                new Types.Named("Option", List.of(readonlyView)),
                new Types.Named("Result", List.of(readonlyView, Types.Primitive.INT)),
                new Types.Tuple(List.of(readonlyView)),
                new Types.Union(List.of(readonlyView, Types.Primitive.STRING)))) {
            assertTrue(has(wrapper, Trait.SEND));
            assertFalse(has(wrapper, Trait.SYNC),
                    "readonly alias illegally became transitively Sync: " + wrapper);
        }
    }

    @Test
    void unknownGenericCallableAndCapabilityTypesFailClosed() {
        for (Trait trait : Trait.values()) {
            assertFalse(has(Types.Unknown.INSTANCE, trait));
            assertFalse(has(new Types.Generic("T"), trait));
            assertFalse(has(new Types.Named("UnknownFromHost", List.of()), trait));
            assertFalse(has(new Types.Named("MutexGuard", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Named("ActorRef", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Named("Future", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Function(List.of(Types.Primitive.INT), Types.Primitive.INT), trait));
            assertFalse(has(new Types.ListType(Types.Unknown.INSTANCE), trait));
        }
    }

    @Test
    void mutableAndImmutableBorrowHaveDistinctSendRequirements() {
        Type sendOnly = new Types.Named("SendOnly", List.of());
        ThreadSafetyTraits.NominalProof proof =
                (type, trait) -> type.name().equals("SendOnly") && trait == Trait.SEND;
        assertTrue(ThreadSafetyTraits.has(sendOnly, Trait.SEND, proof));
        assertFalse(ThreadSafetyTraits.has(sendOnly, Trait.SYNC, proof));

        // A shared reference can cross threads only if the referent is Sync.
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, false), Trait.SEND, proof));
        // An exclusive mutable reference can move when the referent is Send.
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, true), Trait.SEND, proof));
        // Neither reference provides shared thread access to a non-Sync referent.
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, true), Trait.SYNC, proof));
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(Types.Primitive.INT, false), Trait.SEND, proof));
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(Types.Primitive.INT, true), Trait.SYNC, proof));
    }

    @Test
    void channelIsSendForSendPayloadButNotYetProvenSync() {
        Type channel = new Types.Named("Channel", List.of(Types.Primitive.INT));
        assertTrue(has(channel, Trait.SEND));
        assertFalse(has(channel, Trait.SYNC));
        assertFalse(has(new Types.Named("Channel", List.of(Types.Unknown.INSTANCE)), Trait.SEND));
        assertFalse(has(new Types.Named("Channel", List.of(new Types.Named("MutexGuard", List.of(Types.Primitive.INT)))), Trait.SEND));
        assertFalse(has(new Types.Named("Channel", List.of()), Trait.SEND));
        assertFalse(has(new Types.Named("Option", List.of(channel)), Trait.SYNC));
    }

    @Test
    void asyncBoundaryStillAcceptsOwnedSendChannels() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc poll(Channel<Option<List<int>>> inbox): int {
                  val Option<List<int>> x = await (nb readch inbox);
                  if x.is_none() then
                    return 0;
                  fi
                  return x.unwrap().length;
                }
                """)));
    }

    @Test
    void threadTraitNeverOverridesExistingTaskOrActorTransportRules() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Channel<Future<int>> inbox): int {
                  return 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Mutex<int> lock): int {
                  return 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Receiver as
                  receive(ActorMail<Channel<int>> mail): void {
                    return;
                  }
                end
                """)));
    }

    @Test
    void deeplyNestedTypeQueriesAreBoundedAndDoNotPoisonOtherChecks() {
        Type nested = Types.Primitive.INT;
        for (int i = 0; i < 8192; i++) {
            nested = new Types.ListType(nested);
        }
        assertFalse(has(nested, Trait.SEND), "unbounded type recursion must fail closed");
        assertFalse(has(nested, Trait.SYNC));
        assertTrue(has(new Types.ListType(Types.Primitive.INT), Trait.SEND));
    }

    @Test
    void deniedBuiltinsCannotBeMadeSafeByNominalProof() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (String name : List.of(
                "ActorRef", "SharedMutex", "MutexGuard", "Future",
                "ExecutionPermit", "ReadLoan", "WriteLoan", "PartitionLoan")) {
            Type capability = new Types.Named(name, List.of(Types.Primitive.INT));
            assertFalse(ThreadSafetyTraits.has(capability, Trait.SEND, permissive), name);
            assertFalse(ThreadSafetyTraits.has(capability, Trait.SYNC, permissive), name);
        }
        assertFalse(ThreadSafetyTraits.has(Types.Unknown.INSTANCE, Trait.SEND, permissive));
        assertFalse(ThreadSafetyTraits.has(new Types.Generic("T"), Trait.SEND, permissive));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Named("Channel", List.of(Types.Unknown.INSTANCE)), Trait.SEND, permissive));
    }

    @Test
    void permissiveNominalProofCannotSkipUnsoundGenericArguments() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (Trait trait : Trait.values()) {
            for (Type unsafe : List.of(
                    Types.Unknown.INSTANCE,
                    new Types.Generic("T"),
                    new Types.Named("ActorRef", List.of(Types.Primitive.INT)),
                    new Types.Named("MutexGuard", List.of(Types.Primitive.INT)),
                    new Types.Function(List.of(), Types.Primitive.INT))) {
                Type wrapped = new Types.Named("UserDefined", List.of(unsafe));
                assertFalse(ThreadSafetyTraits.has(wrapped, trait, permissive),
                        "nominal trait callback bypassed unsound generic " + unsafe);
                Type doublyWrapped = new Types.Named("Outer", List.of(wrapped));
                assertFalse(ThreadSafetyTraits.has(doublyWrapped, trait, permissive));
            }
        }

        Type sendOnly = new Types.Named("SendOnly", List.of());
        ThreadSafetyTraits.NominalProof sendProof =
                (type, trait) -> type.name().equals("SendOnly") && trait == Trait.SEND
                        || type.name().equals("Wrapper");
        assertTrue(ThreadSafetyTraits.has(
                new Types.Named("Wrapper", List.of(sendOnly)), Trait.SEND, sendProof));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Named("Wrapper", List.of(sendOnly)), Trait.SYNC, sendProof));
    }


    @Test
    void legacySharedSafetyWalkerIsBoundedBeforeSendChecks() throws Exception {
        // Exercise the existing checker directly rather than requiring the
        // parser to create an extremely deeply nested generic source type.
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var method = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe", Types.Type.class, java.util.Set.class, java.util.Map.class);
        method.setAccessible(true);
        Type nested = Types.Primitive.INT;
        for (int i = 0; i < 8192; i++) {
            nested = new Types.ListType(nested);
        }
        assertEquals(Boolean.FALSE,
                method.invoke(checker, nested, new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.TRUE,
                method.invoke(checker, Types.Primitive.INT, new java.util.LinkedHashSet<>(), Map.of()));
    }



    @Test
    void deterministicNestedAdversarialTypesNeverGainForgedTraits() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        java.util.Random random = new java.util.Random(20261018L);
        for (int i = 0; i < 1500; i++) {
            Type leaf = switch (i % 4) {
                case 0 -> Types.Unknown.INSTANCE;
                case 1 -> new Types.Generic("T");
                case 2 -> new Types.Named("Future", List.of(Types.Primitive.INT));
                default -> new Types.Named("MutexGuard", List.of(Types.Primitive.INT));
            };
            Type nested = leaf;
            int depth = 1 + random.nextInt(30);
            for (int j = 0; j < depth; j++) {
                nested = switch (random.nextInt(7)) {
                    case 0 -> new Types.ListType(nested);
                    case 1 -> new Types.Tuple(List.of(nested));
                    case 2 -> new Types.Record(Map.of("field", nested), true);
                    case 3 -> new Types.Union(List.of(Types.Primitive.INT, nested));
                    case 4 -> new Types.Named("Option", List.of(nested));
                    case 5 -> new Types.Named("Result", List.of(Types.Primitive.INT, nested));
                    default -> new Types.Named("UserWrapper", List.of(nested));
                };
            }
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(nested, trait, permissive),
                        "unsafe nested type acquired " + trait + " in case " + i);
            }
        }
    }



    @Test
    void actorTransportCannotBeForgedByUnknownNominalTypes() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType",
                Types.Type.class, dev.oreslang.ast.Ast.ActorKind.class,
                boolean.class, String.class);
        validate.setAccessible(true);
        for (String unknown : List.of(
                "UnknownOpaque", "TcpListener", "ExecutionPermit")) {
            var exception = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(checker,
                            new Types.Named(unknown, List.of()),
                            dev.oreslang.ast.Ast.ActorKind.SHARED, false, "message"));
            assertInstanceOf(IllegalArgumentException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("cannot transport unknown nominal type"),
                    exception.getCause().getMessage());
        }
        var malformedRef = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> validate.invoke(checker,
                        new Types.Named("ActorRef", List.of()),
                        dev.oreslang.ast.Ast.ActorKind.SHARED, false, "message"));
        assertInstanceOf(IllegalArgumentException.class, malformedRef.getCause());
        assertTrue(malformedRef.getCause().getMessage().contains("exactly one actor type argument"));
        for (String admitted : List.of("ActorId", "ActorKind")) {
            assertDoesNotThrow(() -> validate.invoke(checker,
                    new Types.Named(admitted, List.of()),
                    dev.oreslang.ast.Ast.ActorKind.SHARED, false, "message"));
        }
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> validate.invoke(
                checker,
                new Types.Named("ActorId", List.of(Types.Primitive.INT)),
                dev.oreslang.ast.Ast.ActorKind.SHARED, false, "message"));
    }

    @Test
    void deeplyNestedActorMailboxTypesFailWithBoundedDiagnostic() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType",
                Types.Type.class, dev.oreslang.ast.Ast.ActorKind.class,
                boolean.class, String.class);
        validate.setAccessible(true);

        Type nested = Types.Primitive.INT;
        for (int i = 0; i < 8192; i++) {
            nested = switch (i % 3) {
                case 0 -> new Types.ListType(nested);
                case 1 -> new Types.Named("Option", List.of(nested));
                default -> new Types.Record(Map.of("cell", nested));
            };
        }
        Type tooDeep = nested;
        for (var actorKind : List.of(
                dev.oreslang.ast.Ast.ActorKind.SHARED,
                dev.oreslang.ast.Ast.ActorKind.PRIVATE,
                dev.oreslang.ast.Ast.ActorKind.UNTRUSTED)) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(checker, tooDeep, actorKind, false, "mailbox"));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("nesting budget"),
                    failure.getCause().getMessage());
        }
        assertDoesNotThrow(() -> validate.invoke(
                checker, Types.Primitive.INT, dev.oreslang.ast.Ast.ActorKind.SHARED,
                false, "mailbox"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void sharedDagTypeGraphDoesNotTriggerExponentialProofWork() {
        // Only 71 physical nodes, but a naive recursive proof would expand
        // 2^70 visits. Identity memoization must keep this bounded.
        java.util.concurrent.atomic.AtomicInteger nominalCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        ThreadSafetyTraits.NominalProof safeLeaf = (type, trait) -> {
            nominalCalls.incrementAndGet();
            return type.name().equals("KnownSafe");
        };
        Type graph = new Types.Named("KnownSafe", List.of());
        for (int i = 0; i < 70; i++) {
            graph = new Types.Tuple(List.of(graph, graph));
        }
        assertTrue(ThreadSafetyTraits.has(graph, Trait.SEND, safeLeaf));
        assertTrue(ThreadSafetyTraits.has(graph, Trait.SYNC, safeLeaf));
        assertEquals(2, nominalCalls.get(),
                "the trusted nominal callback must execute once per top-level trait proof");

        Type bad = new Types.Named("Future", List.of(Types.Primitive.INT));
        for (int i = 0; i < 70; i++) {
            bad = new Types.Tuple(List.of(bad, bad));
        }
        assertFalse(ThreadSafetyTraits.has(bad, Trait.SEND, safeLeaf));
        assertFalse(ThreadSafetyTraits.has(bad, Trait.SYNC, safeLeaf));
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void enormousFlatTypeProofFailsClosedWithinNodeBudget() {
        Type wide = new Types.Tuple(java.util.Collections.nCopies(
                100_001, Types.Primitive.INT));
        assertFalse(has(wide, Trait.SEND),
                "an oversized but shallow type must not cause unbounded proof work");
        // Budgets and memoization are per-query, never stored in a global
        // cache that could poison a subsequent independent derivation.
        assertTrue(has(Types.Primitive.INT, Trait.SEND));
        assertTrue(has(new Types.Tuple(List.of(Types.Primitive.INT)), Trait.SYNC));
    }

    @Test
    void sharedMutexAcceptsSendOwnedDataButRejectsUnprovenPayloads() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good(): void {
                  val lock = SharedMutex.new([1, 2, 3]);
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(SharedMutex<Mutex<int>> lock): void {
                  return;
                }
                """)));
    }
}
