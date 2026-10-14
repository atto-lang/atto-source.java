package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Closed record fields cannot be partially moved without per-slot drop flags. */
final class RecordFieldMoveOwnershipTest {
    private static final String CELL = """
            define class Cell as
                pub let int value = 7;
            end
            fnc consume(Cell c): int { return c.value; }
            """;

    @Test
    void rejectsExplicitTakeOfOwnedRecordField() {
        rejectsUntrackedRecordMove(CELL + """
                pub routine main(): void {
                    const r = (owned: new Cell());
                    const first = consume(rt take r.owned);
                    const second = consume(rt take r.owned);
                    stdio.stdout.write(first + second);
                    return;
                }
                """);
    }

    @Test
    void rejectsExplicitTakeThroughNestedRecordField() {
        rejectsUntrackedRecordMove(CELL + """
                pub routine main(): void {
                    const r = (nested: (owned: new Cell()));
                    stdio.stdout.write(consume(rt take r.nested.owned));
                    return;
                }
                """);
    }

    @Test
    void rejectsExplicitTakeThroughIndexedRecordField() {
        rejectsUntrackedRecordMove(CELL + """
                pub routine main(): void {
                    const r = (items: [new Cell()]);
                    stdio.stdout.write(consume(rt take r.items[0]));
                    return;
                }
                """);
    }

    @Test
    void permitsOrdinaryAliasingReadsAndCopyScalarProjections() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(CELL + """
                pub routine main(): void {
                    const r = (owned: new Cell());
                    stdio.stdout.write(consume(r.owned));
                    stdio.stdout.write(consume(r.owned));
                    return;
                }
                """)));
    }

    @Test
    void permitsRepeatedCopyScalarReadsFromNestedRecord() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                    const r = (inner: (a: 19, b: 23));
                    stdio.stdout.write(r.inner.a + r.inner.b + r.inner.a);
                    return;
                }
                """)));
    }

    @Test
    void preservesNonConsumingReadOnlyBorrowOfOwnedRecordField() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(CELL + """
                fnc inspect(borrow Cell c): int { return c.value; }
                pub routine main(): void {
                    const r = (owned: new Cell());
                    stdio.stdout.write(inspect(rt borrow r.owned));
                    return;
                }
                """)));
    }

    private static void rejectsUntrackedRecordMove(String program) {
        var parsed = Parser.parse(program);
        // Verify TypeChecker alone can resolve the typed call; the separate
        // ownership gate must reject the unsafe record-field projection.
        assertDoesNotThrow(() -> TypeChecker.checkTypes(parsed));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed));
        assertTrue(failure.getMessage().contains("rt take from a record field/index"),
                failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }
}
