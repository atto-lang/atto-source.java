package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BorrowCastLifetimeRegressionTest {
    private static final String BOX = "define class Parcel as pub let int number = 42; end\n";
    private static String program(String body) {
        return BOX + "pub fnc main(): int { " + body + " }";
    }
    private static void reject(String body) {
        String text = program(body);
        var e = assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(Parser.parse(text)));
        assertTrue(e.getMessage().contains("E_LIFETIME"), e::getMessage);
    }
    @Test void explicitOwnerBindingCannotLaunderCast() {
        reject("val original = new Parcel(); val Parcel fake = (borrow! original) as Parcel; return fake.number;");
    }
    @Test void inferredOwnerBindingCannotLaunderCast() {
        reject("val original = new Parcel(); val fake = (borrow! original) as Parcel; return fake.number;");
    }
    @Test void optionalCastCannotPackageLoanAsOwnedData() {
        reject("val original = new Parcel(); val fake = (borrow! original) as? Parcel; return 42;");
    }
    @Test void existingLoanCannotBeCastToOwnedValue() {
        reject("val original = new Parcel(); val loan = borrow! original; val fake = loan as Parcel; return fake.number;");
    }
    @Test void genuineOwnedCastExecutes() throws Exception {
        String source = program("val original = new Parcel(); val Parcel owned = original as Parcel; return owned.number;");
        OresCompiler.parseAndTypeCheck(source);
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertEquals(42L, ctx.eval(Source.newBuilder(OresLanguage.ID, source, "owned-cast-lifetime.atto").build()).asLong());
        }
    }
}
