package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Reject method-value extraction that would bypass direct-call affine checks. */
final class NestedExtractionCallableEscapeTest {
    @Test
    void optionUnwrapAllCannotLoseItsCallSiteStaticDepthProof() {
        rejectsMethodValue("""
                fnc escape(Option<Option<int>> nested): void {
                    val callback = nested.unwrap_all;
                    return;
                }
                """, "Option.unwrap_all");
    }

    @Test
    void optionFlattenCannotEscapeAsAnUntrackedConsumingClosure() {
        rejectsMethodValue("""
                fnc escape(Option<Option<int>> nested): void {
                    val callback = nested.flatten;
                    return;
                }
                """, "Option.flatten");
    }

    @Test
    void futureFlattenCannotEscapeAsAnUntrackedConsumingClosure() {
        rejectsMethodValue("""
                fnc escape(Future<Future<int>> nested): void {
                    val callback = nested.flatten;
                    return;
                }
                """, "Future.flatten");
    }

    @Test
    void directCallsRemainWellTyped() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc extract(Option<Option<int>> nested): int {
                    return nested.unwrap_all();
                }
                fnc flattenOption(Option<Option<int>> nested): Option<int> {
                    return nested.flatten();
                }
                fnc flattenFuture(Future<Future<int>> nested): Future<int> {
                    return nested.flatten();
                }
                """));
    }

    private static void rejectsMethodValue(String source, String operation) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains(operation)
                        && failure.getMessage().contains("invoke it directly"),
                failure.toString());
    }
}
