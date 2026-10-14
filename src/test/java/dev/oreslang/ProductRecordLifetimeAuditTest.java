package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Record-valued product audit. This does not enable unimplemented record
 * argument spread or imply that GraalVM avoids its host JVM collector.
 */
final class ProductRecordLifetimeAuditTest {
    private static void checked(String program) {
        var syntax = Parser.parse(program);
        TypeChecker.check(syntax);
        OwnershipChecker.check(syntax);
    }

    @Test
    void recordInitializersExecuteLexicallyAndOnlyOnce() throws Exception {
        String source = """
                fnc emit(int value): int {
                    stdio.stdout.write(value);
                    return value;
                }
                pub routine main(): void {
                    const r = (c: emit(3), a: emit(1), b: emit(2));
                    stdio.stdout.write(":");
                    stdio.stdout.write(r.a);
                    stdio.stdout.write(r.b);
                    stdio.stdout.write(r.c);
                    return;
                }
                """;
        checked(source);
        assertEquals("312:123", execute(source));
    }

    @Test
    void nestedRecordSurvivesExplicitHostGcMaintenanceRequest() throws Exception {
        String source = """
                pub routine main(): void {
                    const record = (outer: (left: 19, right: 23), text: "persist");
                    const report = process.gc();
                    stdio.stdout.write(record.outer.left + record.outer.right);
                    stdio.stdout.write(":");
                    stdio.stdout.write(record.text);
                    return;
                }
                """;
        checked(source);
        assertEquals("42:persist", execute(source));
    }

    @Test
    void readonlyProjectionCanBePassedToStructuralReadOnlyView() throws Exception {
        String source = """
                fnc read(structural {left: int, right: int} r): int {
                    return r.left + r.right;
                }
                pub routine main(): void {
                    stdio.stdout.write(read((right: 23, left: 19)));
                    return;
                }
                """;
        checked(source);
        assertEquals("42", execute(source));
    }

    @Test
    void readonlyRecordRejectsFieldMutationAndMutableAliases() {
        String mutation = """
                pub routine main(): void {
                    const r = (value: 1);
                    r.value = 2;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> checked(mutation));
        String mutableAlias = """
                pub routine main(): void {
                    const r = (value: 1);
                    let mut alias = r;
                    alias.value = 2;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> checked(mutableAlias));
    }

    @Test
    void borrowedClassReferenceCannotEscapeInOwnedRecord() {
        String program = """
                define class Cell as
                    pub let int value = 7;
                end
                pub routine main(): void {
                    val Cell cell = new Cell();
                    const r = (loan: rt borrow cell, scalar: 42);
                    stdio.stdout.write(r.scalar);
                    return;
                }
                """;
        var parsed = Parser.parse(program);
        // The integrated and standalone paths must both reject the attempt
        // to retain a lexical borrow inside an owned aggregate.
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }

    @Test
    void tupleSpreadCannotSmuggleBorrowIntoValueParameter() {
        String program = """
                define class Cell as
                    pub let int value = 7;
                end
                fnc consume(Cell cell): int { return cell.value; }
                fnc bad(): int {
                    val Cell cell = new Cell();
                    return consume(...tuple(rt borrow cell));
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void mixedOrDuplicateProductLabelsNeverReachExecution() {
        for (String expression : new String[] {
                "(a: 1, a: 2)",
                "(a: 1, 2)",
                "(a: 1, b: 2, a: 3)",
                "(a: 1, b: 2, c: 3, b: 4)"
        }) {
            String source = "pub routine main(): void { const r = "
                    + expression + "; return; }";
            assertThrows(IllegalArgumentException.class, () -> checked(source), expression);
        }
    }

    @Test
    void borrowedTupleCannotSmuggleMoveOnlyElementAsOwnedCallParameter() {
        String program = """
                define class Cell as
                    pub let int value = 7;
                end
                fnc consume(int n, Cell owned): int { return n + owned.value; }
                fnc unsafe(borrow Tuple[int, Cell] loan): int {
                    return consume(...loan);
                }
                """;
        var parsed = Parser.parse(program);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(failure.getMessage().contains(
                "cannot spread non-Copy elements from a borrowed tuple"),
                failure.getMessage());
    }

    private static String execute(String code) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(bytes).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, code, "product-lifetime.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
