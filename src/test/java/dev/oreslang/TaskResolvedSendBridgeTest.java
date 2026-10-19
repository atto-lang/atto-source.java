package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

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
