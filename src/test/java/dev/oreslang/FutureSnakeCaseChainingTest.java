package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical snake_case Future composition is exactly one layer and never
 * changes the explicit nesting semantics of map, await, or flatten.
 *
 * These are executable guest-source regression targets, not proof that
 * CI/AOT has executed on this branch.
 */
final class FutureSnakeCaseChainingTest {
    @Test
    void flatMapChainsOneAsyncStageUnderCanonicalSnakeCaseSpelling() throws Exception {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub async fnc double_it(int number): int { return number * 2; }

                pub suspends fnc main(): void {
                    val Future<int> result = seed().flat_map(|int number| -> {
                        return double_it(number);
                    });
                    stdio.stdout.write(await result);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void mapKeepsNestedFutureAndFlattenRemovesExactlyOneLayer() throws Exception {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub async fnc double_it(int number): int { return number * 2; }

                pub suspends fnc main(): void {
                    val Future<Future<int>> nested = seed().map(|int number| -> {
                        return double_it(number);
                    });
                    val Future<int> flattened = nested.flatten();
                    stdio.stdout.write(await flattened);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void flatMapRejectsNonFutureCallbackResultAtTypeCheck() {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub fnc main(): void {
                    val Future<int> invalid = seed().flat_map(|int number| -> {
                        return number * 2;
                    });
                    return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains(
                "Future.flat_map callback must return Future<T>"), error::getMessage);
    }

    @Test
    void flatMapDoesNotAcceptWrongCallbackInputType() {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub fnc main(): void {
                    val Future<int> invalid = seed().flat_map(|string text| -> {
                        return seed();
                    });
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }


    @Test
    void flatMapDoesNotRecursivelyUnwrapNestedCallbackFutures() throws Exception {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub async fnc double_it(int number): int { return number * 2; }

                pub suspends fnc main(): void {
                    val Future<Future<int>> nested = seed().flat_map(|int number| -> {
                        return double_it(number).map(|int doubled| -> {
                            return double_it(doubled);
                        });
                    });
                    val Future<int> inner = await nested;
                    stdio.stdout.write(await inner);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("84", run(program),
                "flat_map must remove only the Future layer returned by its callback");
    }

    @Test
    void flatMapCannotAssignAnUnflattenedNestedResultToScalarFuture() {
        String program = """
                pub async fnc seed(): int { return 21; }
                pub async fnc double_it(int number): int { return number * 2; }

                pub fnc main(): void {
                    val Future<int> invalid = seed().flat_map(|int number| -> {
                        return double_it(number).map(|int doubled| -> {
                            return double_it(doubled);
                        });
                    });
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program),
                "flat_map must not implicitly flatten Future<Future<int>> to Future<int>");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "future-snake-case-chaining.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
