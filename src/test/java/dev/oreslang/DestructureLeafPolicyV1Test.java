package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DestructureLeafPolicyV1Test {
    private static String source(String body) {
        return "pub fnc main(): void { " + body + " return; }";
    }
    private static void check(String body) {
        OresCompiler.parseAndTypeCheckStrictAtto(source(body));
    }
    @Test void siblingPolicyDoesNotLeak() {
        assertDoesNotThrow(() -> check("const [a, let b, c] = [1, 2, 3]; b = 4;"));
        assertThrows(IllegalArgumentException.class, () -> check("const [a, let b, c] = [1, 2, 3]; c = 4;"));
        assertDoesNotThrow(() -> check("let [const a, b, c] = [1, 2, 3]; b = 4; c = 5;"));
        assertThrows(IllegalArgumentException.class, () -> check("let [const a, b, c] = [1, 2, 3]; a = 4;"));
    }
    @Test void bareMutAndExplicitLeafKinds() {
        assertDoesNotThrow(() -> Parser.parseStrictAtto(source("const [a, mut b, c] = [1,2,3];")));
        assertDoesNotThrow(() -> Parser.parseStrictAtto(source("[const a, let mut b, val c] = [1,2,3];")));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source("[const a, b, val c] = [1,2,3];")));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source("const [a,val mut b,c] = [1,2,3];")));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source("const [a,a] = [1,2];")));
    }
    @Test void captureRemainsExplicitlyUnsupported() {
        // Staging gate, not semantic negative conformance.
        for(String p : new String[]{"(a,b)", "{a,b}", "[a,b]"}) {
            var ex=assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source("const "+p+" as r = foo();")));
            assertTrue(ex.getMessage().contains("E_DESTRUCTURE_CAPTURE_UNSUPPORTED"),ex::getMessage);
        }
    }
}
