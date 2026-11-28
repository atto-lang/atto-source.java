package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Bounded initial unused-loan inference, not general NLL or field splitting. */
final class InferredUnusedLoanLifetimeTest {
    private static final String CLASS = """
            define class Parcel as
              pub let int number = 42;
            end
            """;
    private static String program(String body) {
        return CLASS + "pub fnc main(): int { " + body + " }";
    }
    private static void executes42(String body) throws Exception {
        String source = program(body);
        OresCompiler.parseAndTypeCheck(source);
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42L, context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "inferred-unused-loan.atto").build()).asLong());
        }
    }
    private static void rejects(String body) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program(body)));
        assertTrue(error.getMessage().contains("E_BORROW"), error::getMessage);
    }

    @Test void unusedSharedLoanReleasesOwnerBeforeTake() throws Exception {
        executes42("val original = new Parcel(); val loan = borrow! original; "
                + "val owned = take! original; return owned.number;");
    }
    @Test void unusedExclusiveLoanReleasesOwnerBeforeRead() throws Exception {
        executes42("let mut original = new Parcel(); val loan = borrow! mut original; "
                + "return original.number;");
    }
    @Test void laterSharedUseRetainsLoanAcrossTake() {
        rejects("val original = new Parcel(); val loan = borrow! original; "
                + "val owned = take! original; return owned.number + loan.number;");
    }
    @Test void laterExclusiveUseRetainsLoanAcrossRead() {
        rejects("let mut original = new Parcel(); val loan = borrow! mut original; "
                + "return original.number + loan.number;");
    }
    @Test void branchUseIsNeverAssumedDead() {
        rejects("val original = new Parcel(); val loan = borrow! original; "
                + "val owned = take! original; if true then return loan.number; fi return owned.number;");
    }
}
