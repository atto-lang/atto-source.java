package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real parser admission only; no checked guest or runtime claim. */
final class RecursiveRecordParserTest {
    private static Object parse(String expression) {
        return Parser.parseStrictAtto("pub fnc main(): void { val result = " + expression + "; return; }");
    }
    private static void reject(String expression, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class, () -> parse(expression), expression);
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test void nestedRecordSpellingUsesOneAst() {
        assertEquals(parse("(outer = (inner = 42))"), parse("(\"outer\" = (\"inner\" = 42))"));
        assertEquals(parse("record{outer: record{inner: 42}}"), parse("(outer = (inner = 42))"));
        assertEquals(parse("((x = 1), (y = 2))"), parse("((\"x\" = 1), (\"y\" = 2))"));
        assertEquals(parse("(x = (1, 2), y = (3, 4))"), parse("(\"x\" = (1, 2), \"y\" = (3, 4))"));
    }
    @Test void callArgumentsAreNotAssignmentsOrCoalescedRecords() {
        assertEquals(parse("foo((x = 42))"), parse("foo(\"x\" = 42)"));
        assertEquals(parse("foo(x: 42)"), parse("foo(x = 42)"));
        assertNotEquals(parse("foo(x = 42)"), parse("foo(\"x\" = 42)"));
        assertEquals(parse("foo((x = 1), (y = 2))"), parse("foo(\"x\" = 1, \"y\" = 2)"));
        assertNotEquals(parse("foo((x = 1, y = 2))"), parse("foo(\"x\" = 1, \"y\" = 2)"));
        assertEquals(parse("foo((outer = (inner = 42)))"), parse("foo(\"outer\" = (\"inner\" = 42))"));
        assertEquals(parse("foo((x = (1, 2)), 3)"), parse("foo(\"x\" = (1, 2), 3)"));
        reject("foo(x = 1, \"y\" = 2)", "positional arguments cannot follow named arguments");
        reject("foo(x = y = 42)", "E_RECORD_FIELD");
    }
    @Test void quotedLabelsAreValidatedAndComparedAfterDecoding() {
        assertEquals(parse("(outer_value2 = 42)"), parse("(\"outer_value2\" = 42)"));
        assertEquals(parse("(x = 42)"), parse("(\"x\" /* comment */ = 42)"));
        for (String label : new String[] {"", "2x", "x-y", "x y", "x ", "x.y", "x/*c*/", "return", "x\\n"}) {
            reject("(\"" + label + "\" = 42)", "E_RECORD_LABEL");
            reject("foo(\"" + label + "\" = 42)", "E_RECORD_LABEL");
        }
        reject("('x' = 42)", "E_RECORD_LABEL");
        reject("foo('x' = 42)", "E_RECORD_LABEL");
        reject("(\"x\" = 1, x = 2)", "E_DUPLICATE");
        reject("(\"x\" = 1, :x)", "E_DUPLICATE");
        reject("(\"x\" = 1, \"\\u{78}\" = 2)", "E_DUPLICATE");
    }
    @Test void groupingAndTupleArityAreDistinct() {
        assertEquals(parse("(1)"), parse("1"));
        assertNotEquals(parse("(1,)"), parse("(1)"));
        assertNotEquals(parse("((x = 1),)"), parse("(x = 1)"));
        assertEquals(parse("(x = 1,)"), parse("(x = 1)"));
        assertDoesNotThrow(() -> parse("()"));
        reject("(x = 1, 2)", "E_LITERAL_KIND");
        reject("(2, x = 1)", "E_LITERAL_KIND");
        reject("(x = y = 1)", "E_RECORD_FIELD");
    }
}
