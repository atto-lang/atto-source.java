package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumCaseSourceCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine parser AST -> statically selected declaration identity proof.
 * NOT a claim that TypeChecker or any interpreter/native backend executes it.
 */
final class EnumCaseSourceCallPlanTest {
    private static final String BASE = """
            define enum Foo as A, B, C end
            pub fnc foo(Foo.A a): int { return 10; }
            pub fnc foo(Foo.B b): int { return 20; }
            pub fnc foo(Foo value): int { return 0; }
            """;

    @Test
    void literalCallsAndExplicitlyWidenedConstBindDifferentExactDeclarations() {
        Ast.Program source = Parser.parse(BASE + """
                pub fnc main(): int {
                    const Foo.A narrowed = Foo.A;
                    const Foo widened = Foo.A;
                    foo(Foo.A);
                    foo(narrowed);
                    foo(widened);
                    foo(Foo.C);
                    return 0;
                }
                """);
        EnumCaseSourceCallPlan proof = EnumCaseSourceCallPlan.prepare(source);
        assertEquals(1, proof.familyCount());
        List<EnumCaseSourceCallPlan.BoundCall> sites = proof.calls();
        assertEquals(4, sites.size());
        assertEquals(List.of("Foo.A", "Foo.A", "Foo", "Foo.C"),
                sites.stream().map(s -> s.staticArgumentTypes().getFirst().name()).toList());
        assertEquals(List.of("Foo.A", "Foo.A", "Foo", "Foo"),
                sites.stream().map(s -> s.exactFunction().parameters()
                        .getFirst().type().name()).toList());
        assertEquals(sites.get(0).exactSlot(), sites.get(1).exactSlot());
        assertNotEquals(sites.get(0).exactSlot(), sites.get(2).exactSlot());
        assertEquals(sites.get(2).exactSlot(), sites.get(3).exactSlot());
        Ast.FunctionDecl main = source.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals("main"))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        Ast.CallExpr parsedCall = (Ast.CallExpr) ((Ast.ExprStmt) main.body().get(2)).expression();
        assertSame(sites.getFirst(), proof.requireExactCall(source, parsedCall));

        // Recreating an equal-looking AST must NEVER be proof of a binding.
        Ast.CallExpr clone = new Ast.CallExpr(parsedCall.callee(),
                parsedCall.arguments());
        assertEquals(parsedCall, clone);
        assertThrows(IllegalArgumentException.class,
                () -> proof.requireExactCall(source, clone));
        assertThrows(IllegalArgumentException.class,
                () -> proof.requireExactCall(Parser.parse(BASE + """
                        pub fnc main(): int { foo(Foo.A); return 0; }
                        """), parsedCall));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(source));
    }

    @Test
    void forgedCaseAndInvalidWideningCannotProvideStaticCallProof() {
        for (String body : List.of(
                "foo(Foo.Missing);",
                "const Foo.A bad = Foo.B; foo(bad);",
                "const Foo.Missing bad = Foo.A; foo(bad);",
                "foo(Unrelated.A);",
                "foo(foo(Foo.A));",
                "foo(unknown);",
                "const Foo Foo = Foo.A; foo(Foo.A);",
                "let Foo v = Foo.A; foo(v);")) {
            Ast.Program source = Parser.parse(BASE +
                    "pub fnc main(): int { " + body + " return 0; }");
            assertThrows(IllegalArgumentException.class,
                    () -> EnumCaseSourceCallPlan.prepare(source), body);
        }
    }

    @Test
    void staticParentCannotImplicitlyBecomeCaseSpecialization() {
        Ast.Program source = Parser.parse("""
                define enum Foo as A, B end
                fnc foo(Foo.A a): int { return 10; }
                fnc foo(Foo.B b): int { return 20; }
                fnc main(): int {
                    const Foo value = Foo.A;
                    foo(value);
                    return 0;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceCallPlan.prepare(source));
    }

    @Test
    void importedOrDynamicCallsAndEffectsStayFailClosed() {
        for (String body : List.of(
                "const Foo.A x = Foo.A; x(Foo.A);",
                "return foo(Foo.A);",
                "foo(Foo.A); const Foo v = Foo.B;",
                "if true then foo(Foo.A); fi")) {
            Ast.Program source = Parser.parse(BASE +
                    "pub fnc main(): int { " + body + " return 0; }");
            assertThrows(IllegalArgumentException.class,
                    () -> EnumCaseSourceCallPlan.prepare(source), body);
        }
    }
}
