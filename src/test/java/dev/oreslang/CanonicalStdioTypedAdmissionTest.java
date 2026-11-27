package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CanonicalStdioTypedAdmissionTest {
    private static void checkOnly(String source) {
        TypeChecker.checkCanonicalStdoutTypesAndOwnershipOnly(Parser.parse(source));
    }
    @Test void twoCanonicalAliasesAreTypedWithoutSuspensionAnnotations() {
        String source = "fnc output(): void { stdio.out.write(\"a\"); process.stdout.write(\"b\"); return; }";
        assertDoesNotThrow(() -> checkOnly(source));
        var error=assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("STDOUT_RUNTIME_PARK_LOWERING_UNVERIFIED"), error.getMessage());
    }
    @Test void rejectWrongArgumentsAndTransitiveLiveBorrow() {
        for(String expr : new String[]{"stdio.out.write(42)", "process.stdout.write()", "stdio.out.write(\"a\", \"b\")"})
            assertThrows(IllegalArgumentException.class, () -> checkOnly("fnc output(): void { "+expr+"; return; }"));
        String program="fnc bad(borrow int x): void { middle(); return; } "
                +"fnc middle(): void { leaf(); return; } fnc leaf(): void { process.stdout.write(\"x\"); return; }";
        var error=assertThrows(IllegalArgumentException.class, () -> checkOnly(program));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }
    @Test void canonicalEndpointAndMethodCannotBeReifiedEvenInCheckOnlyLane() {
        for (String expr : new String[]{"stdio.out", "process.stdout", "stdio.out.write", "process.stdout.write"}) {
            var error=assertThrows(IllegalArgumentException.class,
                    () -> checkOnly("fnc f(): void { val endpoint = "+expr+"; return; }"));
            assertTrue(error.getMessage().contains("direct-write-only"), error.getMessage());
        }
    }
    @Test void checkedOrdinaryCallChainRetainsDistinctIdenticalWriteSites() {
        String source = "fnc leaf(): void { stdio.out.write(\"x\"); stdio.out.write(\"x\"); return; } "
                + "fnc middle(): void { leaf(); return; } fnc root(): int { middle(); process.stdout.write(\"y\"); return 42; }";
        assertDoesNotThrow(() -> checkOnly(source));
    }
    @Test void checkOnlyDoesNotLaunderMutuallyRecursiveParkingIntoNonparkingBorrow() {
        String source = "fnc first(): void { second(); return; } "
                + "fnc second(): void { first(); process.stdout.write(\"x\"); return; } "
                + "fnc bad(borrow int x): void { first(); return; }";
        var error = assertThrows(IllegalArgumentException.class, () -> checkOnly(source));
        assertTrue(error.getMessage().contains("borrow"), error.getMessage());
    }

}
