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
            assertTrue(has(new Types.BoolLiteral(true), trait));
            assertTrue(has(new Types.Named("ActorId", List.of()), trait));
            assertTrue(has(new Types.Named("ActorKind", List.of()), trait));
            assertFalse(has(new Types.Named("ActorId", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Named("ActorKind", List.of(Types.Primitive.INT)), trait));
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
    void malformedNominalNamesDenyProofBeforeNominalCallback() {
        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (Trait trait : Trait.values()) {
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("", List.of()), trait, permissive));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("   ", List.of()), trait, permissive));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named(null, List.of()), trait, permissive));
        }
    }

    @Test
    void malformedNominalNamesNeverBecomeAssignable() {
        Type valid = new Types.Named("Known", List.of());
        for (String invalid : new String[] {null, "", "   "}) {
            Type malformed = new Types.Named(invalid, List.of());
            assertFalse(Types.isAssignable(malformed, malformed),
                    "malformed nominal equality must not become an assignability proof");
            assertFalse(Types.isAssignable(malformed, valid));
            assertFalse(Types.isAssignable(valid, malformed));
            for (Type wrapped : List.of(
                    new Types.Named("Option", List.of(malformed)),
                    new Types.ListType(malformed),
                    new Types.Tuple(List.of(malformed)),
                    new Types.Record(Map.of("value", malformed)),
                    new Types.Borrow(malformed, false),
                    new Types.Function(List.of(Types.Primitive.INT), malformed))) {
                assertFalse(Types.isAssignable(wrapped, wrapped),
                        "equal outer aggregate must not hide malformed nested nominal: " + wrapped);
            }
        }
    }

    @Test
    void malformedNestedNominalsCannotExploitTypeShortcuts() {
        Type malformed = new Types.Named(null, List.of());
        Type nested = new Types.Record(Map.of(
                "payload", new Types.Named("Option", List.of(malformed))));

        assertFalse(Types.isAssignable(nested, Types.Unknown.INSTANCE),
                "Unknown compatibility must not bless malformed nested metadata");
        assertFalse(Types.isAssignable(nested, new Types.Generic("T")),
                "generic compatibility must not bless malformed nested metadata");
        assertFalse(Types.areProvablyDisjoint(
                nested,
                new Types.Record(Map.of(
                        "payload", new Types.Named(
                                "Option", List.of(Types.Primitive.INT))))),
                "malformed nested metadata cannot become a negative-type proof");
        assertFalse(Types.isAssignable(
                nested,
                Types.notOf(new Types.Record(Map.of(
                        "payload", new Types.Named(
                                "Option", List.of(Types.Primitive.INT)))))),
                "Not<T> admission must fail closed on malformed nested metadata");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void malformedTypeValidationIsBoundedBeforeEqualityShortcuts() {
        Type healthy = Types.Primitive.INT;
        for (int i = 0; i < 200; i++) {
            healthy = new Types.ListType(healthy);
        }
        assertTrue(Types.isAssignable(healthy, healthy));

        Type tooDeep = Types.Primitive.INT;
        for (int i = 0; i < 8192; i++) {
            tooDeep = new Types.ListType(tooDeep);
        }
        assertFalse(Types.isAssignable(tooDeep, tooDeep),
                "pathological direct Type graphs must deny instead of overflowing");
        assertFalse(Types.areProvablyDisjoint(
                tooDeep, Types.Primitive.STRING),
                "disjointness proof must also obey structural validation budget");
    }

    @Test
    void malformedNonNominalMetadataFailsClosed() {
        List<Type> malformed = List.of(
                new Types.Generic(null),
                new Types.Generic("   "),
                new Types.StringLiteral(null),
                new Types.ListType(null),
                new Types.Borrow(null, false),
                new Types.ClassNamespace(null),
                new Types.ClassNamespace("   "),
                new Types.Function(List.of(Types.Primitive.INT), (Type) null),
                new Types.Record(Map.of("", Types.Primitive.INT)));

        ThreadSafetyTraits.NominalProof permissive = (type, trait) -> true;
        for (Type type : malformed) {
            assertFalse(Types.isStructurallyWellFormed(type), type.toString());
            assertFalse(Types.isAssignable(type, type), type.toString());
            assertFalse(Types.areProvablyDisjoint(type, Types.Primitive.STRING), type.toString());
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(type, trait, permissive),
                        "malformed metadata cannot receive " + trait + ": " + type);
            }
        }

        assertTrue(Types.isStructurallyWellFormed(new Types.Generic("T")));
        assertTrue(Types.isStructurallyWellFormed(new Types.StringLiteral("")));
        assertTrue(Types.isStructurallyWellFormed(new Types.ClassNamespace("Worker")));
        assertTrue(Types.isStructurallyWellFormed(
                new Types.Record(Map.of("value", Types.Primitive.INT))));
    }

    @Test
    void legacySharedSafetyAndActorBoundariesDenyMalformedNominals() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();

        var sharedSafe = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe", Types.Type.class, java.util.Set.class, java.util.Map.class);
        sharedSafe.setAccessible(true);

        var actorBoundary = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType",
                Types.Type.class, dev.oreslang.ast.Ast.ActorKind.class,
                boolean.class, String.class);
        actorBoundary.setAccessible(true);

        var asyncBoundary = TypeChecker.class.getDeclaredMethod(
                "validateAsyncBoundaryType", Types.Type.class, boolean.class, String.class);
        asyncBoundary.setAccessible(true);

        for (String invalid : new String[] {null, "", "   "}) {
            Type malformed = new Types.Named(invalid, List.of());
            assertEquals(Boolean.FALSE, sharedSafe.invoke(
                    checker, malformed, new java.util.LinkedHashSet<>(), Map.of()));

            for (boolean completion : List.of(false, true)) {
                var actorFailure = assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> actorBoundary.invoke(
                                checker, malformed,
                                dev.oreslang.ast.Ast.ActorKind.SHARED,
                                completion, "malformed actor boundary"));
                assertInstanceOf(IllegalArgumentException.class, actorFailure.getCause());
                assertTrue(actorFailure.getCause().getMessage().contains("malformed type metadata"),
                        actorFailure.getCause().getMessage());
            }

            var asyncFailure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> asyncBoundary.invoke(
                            checker, malformed, false, "malformed async boundary"));
            assertInstanceOf(IllegalArgumentException.class, asyncFailure.getCause());
            assertTrue(asyncFailure.getCause().getMessage().contains("malformed type metadata"),
                    asyncFailure.getCause().getMessage());
        }

        Type malformedTarget = new Types.Named(
                "ActorRef", List.of(new Types.Named(null, List.of())));
        var targetFailure = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> actorBoundary.invoke(
                        checker, malformedTarget,
                        dev.oreslang.ast.Ast.ActorKind.SHARED,
                        false, "actor ref"));
        assertInstanceOf(IllegalArgumentException.class, targetFailure.getCause());
        assertTrue(targetFailure.getCause().getMessage().contains("malformed type metadata"),
                targetFailure.getCause().getMessage());
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
    void asyncBoundaryAcceptsSealedActorScalarMetadata() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc echo_actor_id(ActorId id): ActorId {
                  return id;
                }
                async fnc echo_actor_kind(ActorKind kind): ActorKind {
                  return kind;
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
        assertEquals(Boolean.TRUE,
                method.invoke(checker, new Types.BoolLiteral(true),
                        new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.TRUE,
                method.invoke(checker, new Types.Named("ActorId", List.of()),
                        new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.TRUE,
                method.invoke(checker, new Types.Named("ActorKind", List.of()),
                        new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.FALSE,
                method.invoke(checker,
                        new Types.Named("ActorId", List.of(Types.Primitive.INT)),
                        new java.util.LinkedHashSet<>(), Map.of()));
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
        assertDoesNotThrow(() -> validate.invoke(checker,
                new Types.BoolLiteral(true),
                dev.oreslang.ast.Ast.ActorKind.SHARED, false, "message"));
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
    void traitCacheCannotReuseShallowProofPastMaximumNestingDepth() {
        // The same identity appears first at depth 1, then at depth 2.
        // Shallow success must not mask an over-depth leaf on the second path.
        Type shared = Types.Primitive.INT;
        for (int i = 0; i < 255; i++) {
            shared = new Types.ListType(shared);
        }
        Type shallower = shared;
        Type twoPaths = new Types.Tuple(List.of(
                shallower, new Types.ListType(shallower)));
        assertTrue(ThreadSafetyTraits.has(
                shallower, Trait.SEND, (type, trait) -> false));
        assertFalse(ThreadSafetyTraits.has(
                twoPaths, Trait.SEND, (type, trait) -> false),
                "memoizing only node identity and trait bypasses depth admission");

        // The healthy short path must remain valid in a fresh invocation.
        assertTrue(ThreadSafetyTraits.has(
                shallower, Trait.SEND, (type, trait) -> false));
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
    @org.junit.jupiter.api.Timeout(10)
    void actorTransportAndFrozenResultWalkersRespectProofWorkBudget() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType", Types.Type.class,
                dev.oreslang.ast.Ast.ActorKind.class, boolean.class, String.class);
        validate.setAccessible(true);

        // Only 36 distinct structural type nodes, but without a work budget
        // the two child edges would require over 2^35 visits.
        Type dag = Types.Primitive.INT;
        for (int i = 0; i < 35; i++) {
            dag = new Types.Tuple(List.of(dag, dag));
        }
        Type sharedDag = dag;
        for (boolean completion : List.of(false, true)) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(checker, sharedDag,
                            dev.oreslang.ast.Ast.ActorKind.SHARED,
                            completion, "actor type graph"));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("nesting budget"),
                    failure.getCause().getMessage());
        }

        Type wide = new Types.Tuple(java.util.Collections.nCopies(
                100_001, Types.Primitive.INT));
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> validate.invoke(checker, wide,
                        dev.oreslang.ast.Ast.ActorKind.SHARED,
                        false, "actor flat payload"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("nesting budget"));

        // Exhausted budgets cannot poison a subsequent compiler query.
        assertDoesNotThrow(() -> validate.invoke(checker,
                Types.Primitive.INT, dev.oreslang.ast.Ast.ActorKind.SHARED,
                false, "scalar"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void legacySharedSafetyRejectsExponentialDagsAndOversizedFlatTypes() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();
        var sharedSafe = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe", Types.Type.class, java.util.Set.class, java.util.Map.class);
        sharedSafe.setAccessible(true);

        // A 35-level pair-sharing DAG has 36 physical type nodes but
        // over 2^35 logical traversals without a work budget.
        Type dag = Types.Primitive.INT;
        for (int i = 0; i < 35; i++) {
            dag = new Types.Tuple(List.of(dag, dag));
        }
        assertEquals(Boolean.FALSE, sharedSafe.invoke(checker, dag,
                new java.util.LinkedHashSet<>(), Map.of()));

        Type wide = new Types.Tuple(java.util.Collections.nCopies(
                100_001, Types.Primitive.INT));
        assertEquals(Boolean.FALSE, sharedSafe.invoke(checker, wide,
                new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.TRUE, sharedSafe.invoke(checker,
                new Types.Tuple(List.of(Types.Primitive.INT)),
                new java.util.LinkedHashSet<>(), Map.of()));
    }

    @Test
    void actorBoundaryRejectsForgedDataContainerArities() throws Exception {
        var constructor = TypeChecker.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var checker = constructor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType", Types.Type.class,
                dev.oreslang.ast.Ast.ActorKind.class, boolean.class, String.class);
        validate.setAccessible(true);
        for (var forged : List.of(
                new Types.Named("Option", List.of()),
                new Types.Named("Option", List.of(Types.Primitive.INT, Types.Primitive.INT)),
                new Types.Named("Result", List.of(Types.Primitive.INT)),
                new Types.Named("Result", List.of(Types.Primitive.INT,
                        Types.Primitive.INT, Types.Primitive.INT)),
                new Types.Named("OptionUnwrapError", List.of(Types.Primitive.INT)))) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(checker, forged,
                            dev.oreslang.ast.Ast.ActorKind.SHARED,
                            false, "mailbox value"));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("type arguments"),
                    failure.getCause().getMessage());
        }
        for (var valid : List.of(
                new Types.Named("Option", List.of(Types.Primitive.INT)),
                new Types.Named("Result", List.of(Types.Primitive.INT, Types.Primitive.STRING)),
                new Types.Named("OptionUnwrapError", List.of()))) {
            assertDoesNotThrow(() -> validate.invoke(checker, valid,
                    dev.oreslang.ast.Ast.ActorKind.SHARED, false, "mailbox value"));
        }
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
