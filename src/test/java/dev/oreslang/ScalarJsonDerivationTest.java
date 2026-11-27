package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class ScalarJsonDerivationTest {
    private static final String DECL = """
        define class Order as
          @ToJson("id")
          pub val int id;
          @ToJson("active")
          pub val bool active;
          pub val int private_token;
        end
        """;

    @Test void generatedMethodIsOrdinaryCheckedAstAndExpansionIsIdempotent() {
        Ast.Program expanded = AnnotationExpander.expand(Parser.parse(DECL));
        assertEquals(expanded, AnnotationExpander.expand(expanded));
        Ast.Program checked = TypeChecker.check(Parser.parse(DECL));
        Ast.ClassDecl cls = checked.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl).map(d -> (Ast.ClassDecl)d).findFirst().orElseThrow();
        Ast.MethodDecl method = cls.methods().stream().filter(m -> m.name().equals("to_json")).findFirst().orElseThrow();
        assertEquals(0, method.arity());
        assertEquals("Result", method.returnType().name());
        assertEquals(Ast.Visibility.PUBLIC, method.visibility());
        assertFalse(method.suspends());
    }

    @Test void actualGuestEmitsExactBytesAndDoesNotExportUnannotatedPublicField() throws Exception {
        String program = DECL + """
            pub fnc main(): void {
              val Order first = new Order(9223372036854775807, true, 123456);
              val Order second = new Order(-9223372036854775807 - 1, false, 789);
              stdio.println(first.to_json().unwrap());
              stdio.println(second.to_json().unwrap());
              return;
            }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, program, "scalar-json.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("{\"id\":9223372036854775807,\"active\":true}\n"
                + "{\"id\":-9223372036854775808,\"active\":false}\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n","\n"));
    }

    @Test void rejectsDuplicateKeysPrivateFieldsUnsupportedTypesAndMethodCollision() {
        for (String source : new String[] {
            "define class Bad as @ToJson(\"x\") pub val int x; @ToJson(\"x\") pub val int y; end",
            "define class Bad as @ToJson(\"x\") val int x; end",
            "define class Bad as @ToJson(\"x\") pub val String x; end",
            "define class Bad as @ToJson(\"x\") pub val int x; pub to_json(): int { return 1; } end",
            "define class Bad as @ToJson(\"a b\") pub val int x; end"
        }) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> AnnotationExpander.expand(Parser.parse(source)), source);
            assertTrue(ex.getMessage().contains("E_TO_JSON"), ex.getMessage());
        }
    }

    @Test void duplicateAnnotationIsRejectedAtParserBoundary() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Parser.parse(
            "define class Bad as @ToJson(\"x\") @ToJson(\"y\") pub val int x; end"));
        assertTrue(ex.getMessage().contains("duplicate @ToJson annotation"), ex.getMessage());
    }

    @Test void projectionFieldCountIsBounded() {
        StringBuilder source = new StringBuilder("define class TooWide as\n");
        for (int i = 0; i < 33; i++)
            source.append("@ToJson(\"k").append(i).append("\") pub val int f").append(i).append(";\n");
        source.append("end");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> AnnotationExpander.expand(Parser.parse(source.toString())));
        assertTrue(ex.getMessage().contains("E_TO_JSON: too many projected fields"), ex.getMessage());
    }

    @Test void noImplicitMethodsOnUnannotatedClass() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
            define class Ordinary as pub val int value; end
            pub fnc main(): int { val Ordinary x = new Ordinary(42); return x.to_json(); }
            """)));
    }
}
