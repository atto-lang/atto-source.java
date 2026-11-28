package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class UnusedScalarFieldLoanLifetimeTest {
    private static final String TYPES = """
            define class Parcel as
              pub let int number = 1;
              pub let bool flag = false;
            end
            """;

    private static String program(String body) {
        return TYPES + "pub fnc main(): int { " + body + " }";
    }

    private static void execute42(String body) throws Exception {
        String source = program(body);
        OresCompiler.parseAndTypeCheck(source);
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42L, ctx.eval(Source.newBuilder(OresLanguage.ID, source,
                    "unused-field-loan.atto").build()).asLong());
        }
    }

    private static void reject(String body) {
        var e = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(program(body))));
        assertTrue(e.getMessage().contains("E_BORROW"), e::getMessage);
    }

    @Test void unusedFieldLoanEndsBeforeSameFieldWrite() throws Exception {
        execute42("let mut owner = new Parcel(); val loan = borrow! owner.number; owner.number = 42; return owner.number;");
    }

    @Test void unusedBoolFieldLoanEndsBeforeOwnerTake() throws Exception {
        execute42("let mut owner = new Parcel(); val loan = borrow! owner.flag; val owned = take! owner; return owned.number + 41;");
    }

    @Test void laterLoanUsePreventsExpiry() {
        reject("let mut owner = new Parcel(); val loan = borrow! owner.number; owner.number = 42; stdio.println(loan); return owner.number;");
    }

    @Test void anotherLiveLoanStillBlocksMutation() {
        reject("let mut owner = new Parcel(); val live = borrow! owner.number; val unused = borrow! owner.flag; owner.number = 42; stdio.println(live); return owner.number;");
    }
}
