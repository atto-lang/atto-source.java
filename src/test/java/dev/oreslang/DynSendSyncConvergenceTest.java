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
    private static final ThreadSafetyTraits.NominalProof TRUSTED_CORE = new ThreadSafetyTraits.NominalProof() {
        @Override
        public boolean has(Types.Named type, Trait trait) {
            return false;
        }

        @Override
        public boolean trustedIntrinsic(Types.Named type) {
            return java.util.Set.of("Option", "Result", "Channel",
                    "OptionUnwrapError", "IteratorResult").contains(type.name());
        }
    };
    private static final ThreadSafetyTraits.NominalProof PERMISSIVE_TRUSTED_CORE =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(Types.Named type, Trait trait) {
                    return true;
                }

                @Override
                public boolean trustedIntrinsic(Types.Named type) {
                    return TRUSTED_CORE.trustedIntrinsic(type);
                }
            };
    private static final ThreadSafetyTraits.NominalProof NO_NOMINAL = TRUSTED_CORE;

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
                assertFalse(ThreadSafetyTraits.has(type, trait, PERMISSIVE_TRUSTED_CORE),
                        "dyn escaped trusted container or nominal derivation: " + type);
            }
        }
    }

    @Test
    void unloweredArcWeakAndLockNamesCannotForgeIntrinsicTraits() {
        for (String name : List.of(
                "Arc", "Weak", "Rc", "RcWeak", "Cell", "RefCell",
                "Mutex", "RwLock", "SharedMutex", "MutexGuard", "ReadGuard",
                "WriteGuard", "RawPointer", "NativePointer", "ActorRef",
                "ExecutionPermit", "ThreadLocal", "Task", "ActorGroup",
                "ActorContext", "FfiHandle", "ThreadBound", "OresPointer",
                "BorrowGuard", "AtomicRef", "Shared", "UnsafeCell")) {
            Type alleged = new Types.Named(name, List.of(Types.Primitive.INT));
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(alleged, trait, PERMISSIVE),
                        "nominal callback manufactured runtime guarantee for " + name);
            }
        }
    }

    @Test
    void genericNominalCallbackCannotSpoofCoreIntrinsicNames() {
        for (Types.Named alleged : List.of(
                new Types.Named("Option", List.of(Types.Primitive.INT)),
                new Types.Named("Result", List.of(
                        Types.Primitive.INT, Types.Primitive.STRING)),
                new Types.Named("Channel", List.of(Types.Primitive.INT)),
                new Types.Named("IteratorResult", List.of(Types.Primitive.INT)),
                new Types.Named("OptionUnwrapError", List.of()))) {
            for (Trait trait : Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(alleged, trait, PERMISSIVE),
                        "untrusted callback manufactured intrinsic " + alleged);
            }
        }
        assertTrue(ThreadSafetyTraits.has(
                new Types.Named("Option", List.of(Types.Primitive.INT)),
                Trait.SEND, TRUSTED_CORE));
        assertTrue(ThreadSafetyTraits.has(
                new Types.Named("Result", List.of(
                        Types.Primitive.INT, Types.Primitive.STRING)),
                Trait.SYNC, TRUSTED_CORE));
        assertTrue(ThreadSafetyTraits.has(
                new Types.Named("Channel", List.of(Types.Primitive.INT)),
                Trait.SEND, TRUSTED_CORE));
        assertFalse(ThreadSafetyTraits.has(
                new Types.Named("Channel", List.of(Types.Primitive.INT)),
                Trait.SYNC, TRUSTED_CORE));
    }

    @Test
    void malformedCompilerTypesNeverProduceTraitOrDetachedTaskProofs() {
        for (Trait trait : Trait.values()) {
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named(null, List.of()), trait, PERMISSIVE_TRUSTED_CORE));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named(" ", List.of()), trait, PERMISSIVE_TRUSTED_CORE));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.StringLiteral(null), trait, PERMISSIVE_TRUSTED_CORE));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Record(Map.of("", Types.Primitive.INT)),
                    trait, PERMISSIVE_TRUSTED_CORE));
        }
        Type malformed = new Types.Record(Map.of(" ", Types.Primitive.INT));
        assertFalse(ThreadSafetyTraits.isOwnedSend(malformed, PERMISSIVE_TRUSTED_CORE));
        assertFalse(ThreadSafetyTraits.isOwnedSend(
                new Types.Named("Option", List.of(malformed)),
                PERMISSIVE_TRUSTED_CORE));
    }

    @Test
    void ordinaryImmutableAndMovableValuesKeepTheirProvenTraits() {
        Type immutable = new Types.Record(
                Map.of("count", Types.Primitive.INT), true);
        Type mutable = new Types.ListType(Types.Primitive.INT);
        // readOnly is a view, not proof of a frozen allocation.
        assertTrue(ThreadSafetyTraits.has(immutable, Trait.SEND, NO_NOMINAL));
        assertFalse(ThreadSafetyTraits.has(immutable, Trait.SYNC, NO_NOMINAL));
        assertFalse(ThreadSafetyTraits.isOwnedSend(immutable, NO_NOMINAL),
                "readonly view has no frozen/exclusive backing proof for detached Task");
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(immutable, false),
                Trait.SEND, NO_NOMINAL));
        for (Trait trait : Trait.values()) {
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
    void sourceLevelOptionImpersonationCannotGrantAsyncSend() {
        String program = """
                define class Option<T> as end
                async fnc fake(Option<int> value): int {
                    return 1;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void compilerInternalOptionUnwrapErrorCannotBeNamedBySource() {
        String program = """
                async fnc fake(OptionUnwrapError value): int {
                    return 1;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(failure.getMessage().contains("compiler-internal"),
                failure::getMessage);
    }

    @Test
    void sourceLevelChannelImpersonationCannotGrantAsyncSend() {
        String program = """
                define class Channel<T> as end
                async fnc fake(Channel<int> value): int {
                    return 1;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void ownedSendRejectsEvenRustSendBorrowsUntilTaskScopesHaveLifetimeProofs() {
        Types.Borrow shared = new Types.Borrow(Types.Primitive.INT, false);
        Types.Borrow exclusive = new Types.Borrow(Types.Primitive.INT, true);
        assertTrue(ThreadSafetyTraits.has(shared, Trait.SEND, TRUSTED_CORE));
        assertTrue(ThreadSafetyTraits.has(exclusive, Trait.SEND, TRUSTED_CORE));
        assertFalse(ThreadSafetyTraits.isOwnedSend(shared, TRUSTED_CORE));
        assertFalse(ThreadSafetyTraits.isOwnedSend(exclusive, TRUSTED_CORE));
        assertTrue(ThreadSafetyTraits.isOwnedSend(
                new Types.ListType(Types.Primitive.INT), TRUSTED_CORE));
    }

    @Test
    void nestedOwnedSendCannotHideBorrowInAnyStructuralContainer() {
        Types.Type borrowed = new Types.Borrow(Types.Primitive.INT, false);
        for (Types.Type candidate : List.of(
                new Types.ListType(borrowed),
                new Types.Tuple(List.of(Types.Primitive.INT, borrowed)),
                new Types.Record(Map.of("hidden", borrowed), true),
                new Types.Union(List.of(Types.Primitive.INT, borrowed)),
                new Types.Named("Option", List.of(borrowed)),
                new Types.Named("Result", List.of(Types.Primitive.INT, borrowed)),
                new Types.Function(List.of(borrowed), Types.Primitive.INT))) {
            assertFalse(ThreadSafetyTraits.isOwnedSend(candidate, TRUSTED_CORE),
                    "detached Send leaked a nested borrow in " + candidate);
        }
    }

    @Test
    void detachedOwnedSendRejectsReadonlyRecordViewsEvenWhenNested() {
        Types.Type view = new Types.Record(
                Map.of("value", Types.Primitive.INT), true);
        assertTrue(ThreadSafetyTraits.has(view, Trait.SEND, TRUSTED_CORE),
                "raw Send remains a structural trait query");
        for (Types.Type candidate : List.of(
                view,
                new Types.ListType(view),
                new Types.Tuple(List.of(Types.Primitive.INT, view)),
                new Types.Record(Map.of("nested", view)),
                new Types.Union(List.of(Types.Primitive.INT, view)),
                new Types.Named("Option", List.of(view)),
                new Types.Named("Result", List.of(Types.Primitive.INT, view)))) {
            assertFalse(ThreadSafetyTraits.isOwnedSend(candidate, TRUSTED_CORE),
                    "detached Task admitted readonly alias without backing proof: " + candidate);
        }
    }

    @Test
    void ownedSendPreflightRejectsTypeDepthBombWithoutPoisoningQueries() {
        Types.Type deep = Types.Primitive.INT;
        for (int i = 0; i < 10000; i++) deep = new Types.ListType(deep);
        assertFalse(ThreadSafetyTraits.isOwnedSend(deep, TRUSTED_CORE));
        assertTrue(ThreadSafetyTraits.isOwnedSend(Types.Primitive.INT, TRUSTED_CORE));
    }

    @Test
    void traitProofMemoizesSharedDagAndRejectsWideBudgetBomb() {
        Types.Type shared = Types.Primitive.INT;
        for (int i = 0; i < 200; i++) shared = new Types.ListType(shared);
        java.util.ArrayList<Types.Type> repeated = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) repeated.add(shared);
        assertTrue(ThreadSafetyTraits.has(
                new Types.Tuple(repeated), Trait.SEND, TRUSTED_CORE),
                "shared DAG should reuse a completed proof within the bounded query");

        java.util.ArrayList<Types.Type> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 100_100; i++) wide.add(Types.Primitive.INT);
        assertFalse(ThreadSafetyTraits.has(
                new Types.Tuple(wide), Trait.SEND, TRUSTED_CORE),
                "wide attacker-controlled type must exhaust the global proof budget");
        assertTrue(ThreadSafetyTraits.has(
                Types.Primitive.INT, Trait.SEND, TRUSTED_CORE),
                "budget exhaustion must not poison the next independent query");
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void traitCacheCannotReuseShallowProofPastMaximumNestingDepth() {
        Types.Type shared = Types.Primitive.INT;
        for (int i = 0; i < 255; i++) {
            shared = new Types.ListType(shared);
        }
        Types.Type shallower = shared;
        Types.Type twoPaths = new Types.Tuple(List.of(
                shallower, new Types.ListType(shallower)));

        assertTrue(ThreadSafetyTraits.has(
                shallower, Trait.SEND, TRUSTED_CORE));
        assertFalse(ThreadSafetyTraits.has(
                twoPaths, Trait.SEND, TRUSTED_CORE),
                "memoized shallow proof must not bypass MAX_TYPE_DEPTH");

        assertTrue(ThreadSafetyTraits.has(
                shallower, Trait.SEND, TRUSTED_CORE),
                "fresh query remains valid after rejected deep path");
    }

    @Test
    void actorTransportRejectsUnknownNominalsAndGuestShadowedBuiltins() throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType",
                Types.Type.class,
                dev.oreslang.ast.Ast.ActorKind.class,
                boolean.class,
                String.class);
        validate.setAccessible(true);

        for (String unknown : List.of(
                "UnknownOpaque", "NativeHandle", "ExecutionPermit", "TcpListener")) {
            var failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(
                            checker,
                            new Types.Named(unknown, List.of()),
                            dev.oreslang.ast.Ast.ActorKind.SHARED,
                            false,
                            "mailbox"));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
        assertDoesNotThrow(() -> validate.invoke(
                checker,
                new Types.Named("ActorId", List.of()),
                dev.oreslang.ast.Ast.ActorKind.SHARED,
                false,
                "mailbox"));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
                validate.invoke(
                        checker,
                        new Types.Named("ActorId", List.of(Types.Primitive.INT)),
                        dev.oreslang.ast.Ast.ActorKind.SHARED,
                        false,
                        "mailbox"));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                    define class Option<T> as end
                    define actor Receiver as
                        receive(ActorMail<Option<int>> mail): void {
                            return;
                        }
                    end
                    """)),
                "guest class named Option must not acquire builtin actor transport");
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void actorTransportWalkersBoundSharedDagAndFlatPayloadWork() throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var validate = TypeChecker.class.getDeclaredMethod(
                "validateActorCallableBoundaryType",
                Types.Type.class,
                dev.oreslang.ast.Ast.ActorKind.class,
                boolean.class,
                String.class);
        validate.setAccessible(true);

        Types.Type dag = Types.Primitive.INT;
        for (int i = 0; i < 35; i++) {
            dag = new Types.Tuple(List.of(dag, dag));
        }
        Types.Type sharedDag = dag;
        for (boolean completion : List.of(false, true)) {
            var failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(
                            checker,
                            sharedDag,
                            dev.oreslang.ast.Ast.ActorKind.SHARED,
                            completion,
                            "actor type graph"));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("nesting budget"),
                    failure.getCause()::getMessage);
        }

        Types.Type wide = new Types.Tuple(
                java.util.Collections.nCopies(100_001, Types.Primitive.INT));
        var wideFailure = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> validate.invoke(
                        checker,
                        wide,
                        dev.oreslang.ast.Ast.ActorKind.SHARED,
                        false,
                        "actor flat payload"));
        assertInstanceOf(IllegalArgumentException.class, wideFailure.getCause());
        assertTrue(wideFailure.getCause().getMessage().contains("nesting budget"));

        assertDoesNotThrow(() -> validate.invoke(
                checker,
                Types.Primitive.INT,
                dev.oreslang.ast.Ast.ActorKind.SHARED,
                false,
                "scalar"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void sharedSafetyWalkerHasGlobalProofWorkBudget() throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var sharedSafe = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe",
                Types.Type.class,
                java.util.Set.class,
                java.util.Map.class);
        sharedSafe.setAccessible(true);

        Types.Type dag = Types.Primitive.INT;
        for (int i = 0; i < 35; i++) {
            dag = new Types.Tuple(List.of(dag, dag));
        }
        assertEquals(Boolean.FALSE, sharedSafe.invoke(
                checker,
                dag,
                new java.util.LinkedHashSet<>(),
                Map.of()));

        Types.Type wide = new Types.Tuple(
                java.util.Collections.nCopies(100_001, Types.Primitive.INT));
        assertEquals(Boolean.FALSE, sharedSafe.invoke(
                checker,
                wide,
                new java.util.LinkedHashSet<>(),
                Map.of()));

        assertEquals(Boolean.TRUE, sharedSafe.invoke(
                checker,
                Types.Primitive.INT,
                new java.util.LinkedHashSet<>(),
                Map.of()));
    }

    @Test
    void resolvedSharedSafetyRejectsReadonlyAliasCapabilities() throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var sharedSafe = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe",
                Types.Type.class,
                java.util.Set.class,
                java.util.Map.class);
        sharedSafe.setAccessible(true);

        Types.Type writable = new Types.Record(
                Map.of("value", Types.Primitive.INT));
        Types.Type readonly = new Types.Record(
                Map.of("value", Types.Primitive.INT), true);
        Types.Type nestedReadonly = new Types.Record(
                Map.of("view", readonly));

        assertEquals(Boolean.TRUE, sharedSafe.invoke(
                checker, writable, new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.FALSE, sharedSafe.invoke(
                checker, readonly, new java.util.LinkedHashSet<>(), Map.of()));
        assertEquals(Boolean.FALSE, sharedSafe.invoke(
                checker, nestedReadonly, new java.util.LinkedHashSet<>(), Map.of()));
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
