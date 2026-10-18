package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumCaseSourceCallPlan;
import dev.oreslang.types.EnumCaseAotSourceImage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parser AST -> exact eos2 declaration -> closed AOT symbol proof, NOT guest execution. */
final class EnumCaseAotSourceImageTest {
    private static final String CODE = """
            define enum Foo as A, B end
            fnc classify(Foo.A value): int { return 10; }
            fnc classify(Foo.B value): int { return 20; }
            fnc classify(Foo value): int { return 0; }
            fnc main(): int {
                const Foo widened = Foo.A;
                classify(Foo.A);
                classify(widened);
                return 0;
            }
            """;

    @Test
    void exactParsedCallsHaveDifferentSourceBoundDirectRelocations() {
        Ast.Program source = Parser.parse(CODE);
        EnumCaseSourceCallPlan plan = EnumCaseSourceCallPlan.prepare(source);
        assertEquals(3, plan.directModuleSymbols().size());
        Map<String, String> digests = new HashMap<>();
        plan.directModuleSymbols().forEach(symbol -> digests.put(symbol, "a".repeat(64)));
        EnumCaseAotSourceImage image = EnumCaseAotSourceImage.seal(plan, digests);
        assertEquals(3, image.definitionCount());

        Ast.FunctionDecl main = source.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals("main"))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        Ast.CallExpr direct = (Ast.CallExpr) ((Ast.ExprStmt) main.body().get(1)).expression();
        Ast.CallExpr widened = (Ast.CallExpr) ((Ast.ExprStmt) main.body().get(2)).expression();
        var exact = image.relocate(source, direct);
        var fallback = image.relocate(source, widened);
        image.verify(exact, source, direct);
        image.verify(fallback, source, widened);
        assertNotEquals(exact.exactSymbol(), fallback.exactSymbol());
        assertEquals("a".repeat(64), exact.bodyDigest());

        digests.clear();
        image.verify(exact, source, direct); // Immutable compilation snapshot.

        EnumCaseAotSourceImage second = EnumCaseAotSourceImage.seal(plan,
                java.util.Map.of(
                        exact.exactSymbol(), "a".repeat(64),
                        fallback.exactSymbol(), "a".repeat(64),
                        plan.calls().stream().map(EnumCaseSourceCallPlan.BoundCall::exactSlot)
                                .filter(symbol -> !symbol.equals(exact.exactSymbol())
                                        && !symbol.equals(fallback.exactSymbol()))
                                .findAny().orElseGet(() -> plan.directModuleSymbols().stream()
                                        .filter(symbol -> !symbol.equals(exact.exactSymbol())
                                                && !symbol.equals(fallback.exactSymbol()))
                                        .findFirst().orElseThrow()), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> second.verify(exact, source, direct));

        Ast.CallExpr clonedCall = new Ast.CallExpr(direct.callee(), direct.arguments());
        assertEquals(direct, clonedCall);
        assertThrows(IllegalArgumentException.class,
                () -> image.relocate(source, clonedCall));
        assertThrows(IllegalArgumentException.class,
                () -> image.verify(exact, source, clonedCall));
        assertThrows(IllegalArgumentException.class,
                () -> image.verify(exact, Parser.parse(CODE), direct));
    }

    @Test
    void closedWorldRejectsMissingExtraMalformedAndVirtualExports() {
        Ast.Program source = Parser.parse(CODE);
        EnumCaseSourceCallPlan plan = EnumCaseSourceCallPlan.prepare(source);
        Map<String, String> valid = new HashMap<>();
        plan.directModuleSymbols().forEach(name -> valid.put(name, "c".repeat(64)));
        var missing = new HashMap<>(valid);
        missing.remove(missing.keySet().iterator().next());
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseAotSourceImage.seal(plan, missing));
        var extra = new HashMap<>(valid);
        extra.put("unknown.eos2", "f".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseAotSourceImage.seal(plan, extra));
        var uppercase = new HashMap<>(valid);
        uppercase.put(uppercase.keySet().iterator().next(), "F".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseAotSourceImage.seal(plan, uppercase));
    }
}
