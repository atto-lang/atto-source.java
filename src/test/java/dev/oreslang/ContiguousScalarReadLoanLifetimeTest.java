package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContiguousScalarReadLoanLifetimeTest {
    private static final String TYPES = """
            define class Pair as
              pub let int left = 20;
              pub let int right = 22;
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
                    "contiguous-scalar-loan.atto").build()).asLong());
        }
    }
    private static void reject(String body) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(program(body))));
        assertTrue(error.getMessage().contains("E_BORROW"), error::getMessage);
    }
    @Test void sharedLoanExpiresAfterTwoIndependentScalarReads() throws Exception {
        execute42("val owner = new Pair(); val loan = borrow! owner; val a = loan.left; val b = loan.right; val moved = take! owner; return a + b + moved.left - 20;");
    }
    @Test void exclusiveLoanExpiresAfterThreeIndependentScalarReads() throws Exception {
        execute42("let mut owner = new Pair(); val loan = borrow! mut owner; val a = loan.left; val b = loan.right; val c = loan.left; return a + b + c + owner.right - 42;");
    }
    @Test void laterLoanReadPreventsEarlyRelease() {
        reject("val owner = new Pair(); val loan = borrow! owner; val a = loan.left; val b = loan.right; val moved = take! owner; return a + b + loan.left;");
    }
    @Test void interveningUnprovedBindingRetainsLease() {
        reject("val owner = new Pair(); val loan = borrow! owner; val a = loan.left; val other = 1; val b = loan.right; val moved = take! owner; return a + b;");
    }
}
