package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Copied scalar formals only. No new loan or mutable referent authority. */
final class IncomingScalarRebindTest {
    private static void check(String source) {
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
    }

    @Test void typedAndNameFirstScalarFormals() {
        for (String formal : new String[] {"rebind int n", "rebind n: int"}) {
            assertDoesNotThrow(() -> check("fnc bump(" + formal + "): int { n = n + 2; return n; } "
                    + "pub fnc main(): int { const int n = 20; return bump(n) + n; }"));
        }
        assertDoesNotThrow(() -> check("fnc flip(rebind bool b): bool { b = !b; return b; }"));
    }

    @Test void fixedAndUnsupportedFormalsFailClosed() {
        var fixed = assertThrows(IllegalArgumentException.class,
                () -> check("fnc bump(int n): int { n = n + 2; return n; }"));
        assertTrue(fixed.getMessage().contains("E_IMMUTABLE_REBIND"), fixed.getMessage());
        for (String formal : new String[] {"rebind mut int n", "rebind borrow int n", "rebind Option<int> n",
                "rebind rebind int n", "rebind int mut n"}) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc f(" + formal + "): void { return; }"));
            assertTrue(ex.getMessage().contains("E_REBIND_"), ex.getMessage());
        }
    }

    @Test void contextualIdentifierRemainsLegal() {
        assertDoesNotThrow(() -> check("fnc f(int rebind): int { return rebind; }"));
        assertDoesNotThrow(() -> check("fnc f(rebind: int): int { return rebind; }"));
    }
}
