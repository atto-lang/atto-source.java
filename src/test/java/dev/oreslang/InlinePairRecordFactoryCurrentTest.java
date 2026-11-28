package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InlinePairRecordFactoryCurrentTest {
    private static String main(String body) {
        return "pub fnc main(): int { " + body + " }";
    }
    private static Object parse(String expression) {
        return Parser.parseStrictAtto(main("let Record r = " + expression + "; return 0;"));
    }
    @Test void canonicalNamedEntriesShareExistingRecordAst() {
        assertEquals(parse("Record.of(five := 5, three := \"box\")"),
                parse("Record.of(Pair.of(\"five\", 5), Pair.of(\"three\", \"box\"))"));
        assertEquals(parse("Record.of(\"five\" := 5, three := \"box\")"),
                parse("Record.of(five = 5, three = \"box\")"));
        assertEquals(parse("Record.of(five := 5, Pair.of(\"three\", \"box\"))"),
                parse("Record.of(five := 5, three := \"box\")"));
    }
    @Test void duplicateAndInvalidKeysRejectWithoutGuestEvaluation() {
        for (String expression : new String[] {
                "Record.of(five := 5, five := 6)",
                "Record.of(five := 5, Pair.of(\"five\", 6))",
                "Record.of(\"bad-name\" := 5)",
                "Record.of(\"return\" := 5)",
                "Record.of(five : = 5)",
                "Record.of(five := x = 5)",
                "Record.of(five := 5, three = \"box\")"
        }) assertThrows(IllegalArgumentException.class, () -> parse(expression), expression);
    }
    @Test void bareRecordLocalPreservesClosedShapeAndRejectsScalarOrMissingField() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                main("let Record r = Record.of(five := 5, three := \"box\"); return r.five;")));
        for (String body : new String[] {
                "let Record r = 42; return 0;",
                "let Record r = Record.of(five := 5); return r.missing;",
                "let Record r = Record.of(five := \"box\"); return r.five;"
        }) assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(main(body)), body);
    }
    @Test void actualCheckedGuestReadsHeterogeneousRecord() throws Exception {
        String source = main("let Record r = Record.of(five := 5, three := \"box\"); "
                + "return if r.three eq \"box\" then r.five + 37 else 99 fi;");
        var checked = OresCompiler.parseAndTypeCheckStrictAtto(source);
        String filename = "inline-pair-record-factory.atto";
        try (var lease = OresLanguage.openPrecheckedProgram(filename, source, checked);
             var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(Source.newBuilder(OresLanguage.ID, source, filename).build()).asLong());
        }
    }
}
