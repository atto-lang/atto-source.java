package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedValueMatchParameterTest {
    @Test
    void positionalAndNamedReturnTypeRemainArmLocal() throws Exception {
        String guest = """
            fnc value(bool selected): int {
                val int n = match<int> selected over
                    on true -> { return 41; }
                    on false -> { return 1; }
                end;
                return n + 1;
            }
            fnc named(bool selected): string {
                return match<ret=string> selected over {
                    on true { return "ok"; }
                    on false { return "no"; }
                };
            }
            pub fnc main(): void {
                stdio.println(value(true));
                stdio.println(value(false));
                stdio.println(named(true));
                return;
            }
            """;
        var parsed = Parser.parse(guest);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        var fn = (Ast.FunctionDecl) parsed.modules().getFirst().declarations().getFirst();
        var binding = (Ast.BindingStmt) fn.body().getFirst();
        // Ensure callers never accidentally lose the annotation during parsing.
        assertInstanceOf(Ast.MatchExpr.class, binding.initializer());
        assertEquals("int", ((Ast.MatchExpr) binding.initializer()).resultType().name());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "typed-value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42\n2\nok",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void declaredReturnTypeRejectsIncompatibleArm() {
        String guest = """
            fnc bad(bool selected): int {
                return match<int> selected over
                    on true -> { return "bad"; }
                    on false -> { return 4; }
                end;
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test
    void invalidReturnParametersAndVoidReturnFailClosed() {
        for (String parameter : new String[]{"int, string", "type=int", ""}) {
            String guest = "fnc bad(bool x): int { return match<" + parameter +
                    "> x over { on true { return 1; } on false { return 0; } }; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(guest));
        }
        String voidGuest = """
            fnc bad(bool x): int {
                return match<void> x over {
                    on true { return 1; }
                    on false { return 0; }
                };
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(voidGuest)));
    }

    @Test
    void inferredMatchStillAcceptsHistoricalSyntax() {
        String guest = """
            fnc legacy(bool x): int {
                return match x over {
                    on true { return 42; }
                    on false { return 0; }
                };
            }
            """;
        TypeChecker.check(Parser.parse(guest));
    }

    @Test
    void positiveAndNegativeTypePatternsPartitionAUnion() throws Exception {
        String guest = """
            fnc discriminate(int | string v): int {
                return match<int> v over
                    on int as n -> { return 10; }
                    on !int as other -> { return 20; }
                end;
            }
            pub fnc main(): void {
                stdio.println(discriminate(5));
                stdio.println(discriminate("text"));
                return;
            }
            """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, guest, "typed-complement-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("10\\n20", output.toString(StandardCharsets.UTF_8).trim().replace("\\r", ""));
    }

    @Test
    void nonReifiableNegativePatternsFailClosed() {
        String guest = """
            fnc bad(Option<int> v): int {
                return match<int> v over
                    on !Option<int> as other -> { return 1; }
                    on _ -> { return 2; }
                end;
            }
            """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

}
