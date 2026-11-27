package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
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
    @Test void recursiveHeaderPreservesKindsAndPolicies() {
        var root = Parser.parseStrictLocalCaptureHeader("const ({name,age} as person,[x,let mut y,z] as coords) as result");
        assertEquals(Ast.LocalPatternKind.TUPLE, root.kind());
        assertEquals("result",root.capture().name());
        var record = (Ast.LocalPatternGroup) root.items().get(0);
        var array = (Ast.LocalPatternGroup) root.items().get(1);
        assertEquals(Ast.LocalPatternKind.RECORD,record.kind());
        assertEquals("person",record.capture().name());
        assertEquals(Ast.LocalPatternKind.ARRAY,array.kind());
        assertEquals("coords",array.capture().name());
        var y = (Ast.LocalPatternLeaf) array.items().get(1);
        var z = (Ast.LocalPatternLeaf) array.items().get(2);
        assertEquals(Ast.BindingKind.LET,y.policy().kind());
        assertTrue(y.policy().mutableReferent());
        assertEquals(Ast.BindingKind.CONST,z.policy().kind());
        assertFalse(z.policy().mutableReferent());
        var independent = Parser.parseStrictLocalCaptureHeader("[const a,let mut b,val c] as r");
        assertEquals(Ast.BindingKind.CONST,independent.capture().policy().kind());
        var conversion = Parser.parseStrictLocalCaptureHeader("const (a,b) as Record r");
        assertEquals(Ast.LocalPatternKind.TUPLE,conversion.kind());
        assertEquals(Ast.LocalPatternKind.RECORD,conversion.capture().target());
        assertTrue(Parser.parseStrictLocalCaptureHeader("const [] as r").items().isEmpty());
    }
    @Test void recursiveHeaderRejectsMalformedAndUnboundedPatterns() {
        for (String header : new String[]{
                "[const a,b,val c] as r", "const [a,a] as r",
                "const ([a] as x,{b} as x) as r", "const [a,b] as a",
                "const [a,val mut b] as r", "const (a,b] as r",
                "const [a,b] as r garbage", "const [borrow! a] as r"})
            assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictLocalCaptureHeader(header), header);
        String deep = "const " + "[".repeat(70) + "a" + "]".repeat(70) + " as r";
        var failure = assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictLocalCaptureHeader(deep));
        assertTrue(failure.getMessage().contains("E_DESTRUCTURE_LIMIT"),failure::getMessage);
    }
    @Test void captureRemainsExplicitlyUnsupported() {
        // Staging gate, not semantic negative conformance.
        for(String p : new String[]{"(a,b)", "{a,b}", "[a,b]"}) {
            var ex=assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(source("const "+p+" as r = foo();")));
            assertTrue(ex.getMessage().contains("E_DESTRUCTURE_CAPTURE_UNSUPPORTED"),ex::getMessage);
        }
    }
}
