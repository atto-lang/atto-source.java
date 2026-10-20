package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolved semantic admission for scheduled work. These tests intentionally
 * stop at the lowering/quiescence barrier; no Task execution is enabled here.
 */
final class TaskResolvedSendBridgeTest {
    private static IllegalArgumentException reject(String source) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
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
    void erasedDynIsDeniedByCanonicalTaskAndSharedSafetyAuthorities()
            throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var threadSend = TypeChecker.class.getDeclaredMethod(
                "isThreadSend", Types.Type.class);
        threadSend.setAccessible(true);
        var sharedSafe = TypeChecker.class.getDeclaredMethod(
                "isSharedSafe",
                Types.Type.class,
                java.util.Set.class,
                java.util.Map.class);
        sharedSafe.setAccessible(true);

        Types.Type dyn = new Types.Dyn(
                new Types.Named("Worker", java.util.List.of()));
        assertEquals(Boolean.FALSE, threadSend.invoke(checker, dyn));
        assertEquals(Boolean.FALSE, sharedSafe.invoke(
                checker, dyn, new java.util.LinkedHashSet<>(), Map.of()));
    }

    @Test
    void malformedEmbeddingTypesCannotPassResolvedTaskSendAdmission()
            throws Exception {
        var ctor = TypeChecker.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        var checker = ctor.newInstance();
        var threadSend = TypeChecker.class.getDeclaredMethod(
                "isThreadSend", Types.Type.class);
        threadSend.setAccessible(true);

        for (Types.Type malformed : java.util.List.of(
                new Types.Named(null, java.util.List.of()),
                new Types.Named(" ", java.util.List.of()),
                new Types.StringLiteral(null),
                new Types.Record(Map.of(" ", Types.Primitive.INT)),
                new Types.Named("Option", java.util.List.of(
                        new Types.Record(Map.of(" ", Types.Primitive.INT)))))) {
            assertEquals(Boolean.FALSE, threadSend.invoke(checker, malformed),
                    "malformed semantic type must not authorize Task transfer");
        }
    }

    @Test
    void scalarTaskCapturePassesSemanticSendAndStopsAtLoweringBarrier() {
        IllegalArgumentException failure = reject("""
            pub fnc main(): void {
                let int value = 11;
                do nb { stdio.stdout.log(value); };
                return;
            }
            """);
        assertTrue(
                failure.getMessage().contains(
                        "passed resolved owned-Send admission"),
                failure::getMessage);
    }

    @Test
    void futureCaptureFailsResolvedSemanticAdmissionBeforeLowering() {
        IllegalArgumentException failure = reject("""
            pub fnc main(Future<int> pending): void {
                do nb { stdio.stdout.log(pending); };
                return;
            }
            """);
        assertTrue(
                failure.getMessage().contains(
                        "semantic Task admission failed"),
                failure::getMessage);
        assertTrue(
                failure.getMessage().contains(
                        "scheduled Task capture 'pending'"),
                failure::getMessage);
        assertTrue(
                failure.getMessage().contains(
                        "resolved owned-Send"),
                failure::getMessage);
        assertFalse(
                failure.getMessage().contains(
                        "conservative Task Send preflight"),
                failure::getMessage);
    }

    @Test
    void asyncTaskFutureParameterFailsResolvedAdmission() {
        IllegalArgumentException failure = reject("""
            pub fnc main(): void {
                let Task<int> t =
                    (async task |Future<int> pending| -> { return 1; })(7);
                return;
            }
            """);
        assertTrue(
                failure.getMessage().contains(
                        "async task lambda parameter 'pending'"),
                failure::getMessage);
        assertTrue(
                failure.getMessage().contains(
                        "resolved owned-Send"),
                failure::getMessage);
    }

    @Test
    void asyncTaskScalarParameterAndArgumentReachLoweringBarrier() {
        IllegalArgumentException failure = reject("""
            pub fnc main(): void {
                let Task<int> t =
                    (async task |int value| -> { return value; })(7);
                return;
            }
            """);
        assertTrue(
                failure.getMessage().contains(
                        "call passed resolved owned-Send admission"),
                failure::getMessage);
    }

    @Test
    void nestedTaskRevalidatesOuterTaskCapture() {
        IllegalArgumentException failure = reject("""
            pub fnc main(Future<int> pending): void {
                do nb {
                    do nb { stdio.stdout.log(pending); };
                };
                return;
            }
            """);
        assertTrue(
                failure.getMessage().contains(
                        "scheduled Task capture 'pending'"),
                failure::getMessage);
        assertTrue(
                failure.getMessage().contains(
                        "resolved owned-Send"),
                failure::getMessage);
    }
}
