package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InferredScalarReadLoanLifetimeTest {
    private static final String BOX = "define class Parcel as pub let int number = 42; end\n";
    private static String program(String body) {
        return BOX + "pub fnc main(): int { " + body + " }";
    }
    private static void execute42(String body) throws Exception {
        String source = program(body);
        OresCompiler.parseAndTypeCheck(source);
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42L, ctx.eval(Source.newBuilder(OresLanguage.ID,source,"scalar-read-lifetime.atto").build()).asLong());
        }
    }
    private static void reject(String body) {
        var e=assertThrows(IllegalArgumentException.class,()->OwnershipChecker.check(Parser.parse(program(body))));
        assertTrue(e.getMessage().contains("E_BORROW"),e::getMessage);
    }
    @Test void sharedScalarLastReadThenTake() throws Exception {
        execute42("val original = new Parcel(); val loan = borrow! original; val read = loan.number; val owned = take! original; return read + owned.number - 42;");
    }
    @Test void exclusiveScalarLastReadThenOwnerRead() throws Exception {
        execute42("let mut original = new Parcel(); val loan = borrow! mut original; val read = loan.number; return original.number + read - 42;");
    }
    @Test void laterUsePreventsRelease() {
        reject("val original = new Parcel(); val loan = borrow! original; val read = loan.number; val owned = take! original; return read + loan.number;");
    }
    @Test void exclusiveLaterUsePreventsRelease() {
        reject("let mut original = new Parcel(); val loan = borrow! mut original; val read = loan.number; return original.number + read + loan.number;");
    }
    @Test void unknownInterveningStatementRetainsLease() {
        reject("val original = new Parcel(); val loan = borrow! original; val n = 1; val read = loan.number; val owned = take! original; return read;");
    }

    @Test void consecutiveSharedReadsThenTake() throws Exception {
        execute42("val original = new Parcel(); val loan = borrow! original; val first = loan.number; val second = loan.number; val owned = take! original; return first + second + owned.number - 84;");
    }
    @Test void consecutiveExclusiveReadsThenOwnerRead() throws Exception {
        execute42("let mut original = new Parcel(); val loan = borrow! mut original; val first = loan.number; val second = loan.number; return original.number + first + second - 84;");
    }
    @Test void consecutiveReadsWithLaterUseStillReject() {
        reject("val original = new Parcel(); val loan = borrow! original; val first = loan.number; val second = loan.number; val owned = take! original; return first + second + loan.number;");
    }
    @Test void interveningUnknownBindingStillRetainsLease() {
        reject("val original = new Parcel(); val loan = borrow! original; val first = loan.number; val unrelated = 1; val second = loan.number; val owned = take! original; return first + second + unrelated;");
    }
    @Test void anotherLoanStillPreventsOwnerMove() {
        reject("val original = new Parcel(); val live = borrow! original; val loan = borrow! original; val first = loan.number; val second = loan.number; val owned = take! original; return first + second + live.number;");
    }
}
