package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class WithExpressionGuestTest {

    private static String run(String guest) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, "with-ownership.atto")
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

    private static IllegalArgumentException rejects(String guest) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void copyUpdatePreservesOriginalAndEvaluatesOriginalField() throws Exception {
        assertEquals("101120", run("""
                pub routine main(): void {
                    val point = record{x: 10, y: 20};
                    val next = rt copy point with {x => point.x + 1};
                    stdio.stdout.write(point.x);
                    stdio.stdout.write(next.x);
                    stdio.stdout.write(next.y);
                    return;
                }
                """));
    }

    @Test
    void plainCopyValueAndChainedUpdateExecuteAsExpressions() throws Exception {
        assertEquals("104237", run("""
                pub routine main(): void {
                    val point = record{x: 10, y: 20};
                    val next = point with {x => 42} with {y => 37};
                    stdio.stdout.write(point.x);
                    stdio.stdout.write(next.x);
                    stdio.stdout.write(next.y);
                    return;
                }
                """));
    }

    @Test
    void explicitTakeConstructsNewValueAndCannotReadMovedOriginal() throws Exception {
        assertEquals("912", run("""
                pub routine main(): void {
                    val point = record{x: 9, y: 8};
                    val next = (rt take point) with {y => 12};
                    stdio.stdout.write(next.x);
                    stdio.stdout.write(next.y);
                    return;
                }
                """));
        IllegalArgumentException failure = rejects("""
                pub routine main(): int {
                    val point = record{x: 9, y: 8};
                    val next = (rt take point) with {y => 12};
                    return point.x;
                }
                """);
        assertTrue(failure.getMessage().contains("moved"), failure::getMessage);
    }

    @Test
    void unknownFieldAndWrongReplacementTypeReject() {
        IllegalArgumentException unknown = rejects("""
                pub routine main(): int {
                    val point = record{x: 1};
                    return (point with {z => 2}).x;
                }
                """);
        assertTrue(unknown.getMessage().contains("unknown with field"), unknown::getMessage);

        IllegalArgumentException wrongType = rejects("""
                pub routine main(): int {
                    val point = record{x: 1};
                    return (point with {x => "invalid"}).x;
                }
                """);
        assertTrue(wrongType.getMessage().contains("with field"), wrongType::getMessage);
    }

    @Test
    void rejectsBorrowAndNonRecordAndSideEffectfulRhs() {
        IllegalArgumentException borrowed = rejects("""
                pub routine main(): void {
                    val point = record{x: 1};
                    val next = (rt borrow point) with {x => 2};
                    return;
                }
                """);
        assertTrue(borrowed.getMessage().contains("readonly structural record"),
                borrowed::getMessage);

        IllegalArgumentException struct = rejects("""
                pub routine main(): void {
                    val point = infer struct{x: 1};
                    val next = (rt take point) with {x => 2};
                    return;
                }
                """);
        assertTrue(struct.getMessage().contains("readonly structural record"),
                struct::getMessage);

        IllegalArgumentException call = rejects("""
                fnc next_x(): int { return 5; }
                pub routine main(): void {
                    val point = record{x: 1};
                    val next = point with {x => next_x()};
                    return;
                }
                """);
        assertTrue(call.getMessage().contains("non-suspending, effect-safe"),
                call::getMessage);
    }

    @Test
    void rejectsMoveOnlyRecordContentWithoutCopyContract() {
        IllegalArgumentException failure = rejects("""
                define class Box as
                    pub let int value = 0;
                end

                pub routine main(): void {
                    val point = record{x: new Box(), y: 2};
                    val next = rt copy point with {y => 3};
                    return;
                }
                """);
        assertTrue(failure.getMessage().contains("rt copy")
                || failure.getMessage().contains("with v1"),
                failure::getMessage);
    }
}
