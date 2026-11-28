package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class IntrinsicContextualSingletonTupleTest {
    private static String program(String body) {
        return "pub fnc main(): void { " + body + " return; }";
    }
    private static Ast.Expr initializer(String binding) {
        Ast.Program p = Parser.parseStrictAtto(program(binding));
        return p.modules().stream().flatMap(m -> m.declarations().stream())
            .filter(d -> d instanceof Ast.FunctionDecl)
            .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow()
            .body().stream().filter(s -> s instanceof Ast.BindingStmt)
            .map(s -> ((Ast.BindingStmt) s).initializer()).findFirst().orElseThrow();
    }
    @Test void realConstructionOnlyUnderIntrinsicSingletonWitness() {
        assertEquals(initializer("const [int] t=(42,);"), initializer("const [int] t=(42);"));
        assertInstanceOf(Ast.TupleExpr.class, initializer("const [int] t=(42);"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("const t=(42);"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("const int t=(42);"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("const [int] t=42;"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("const [int,int] t=(42);"));
        assertInstanceOf(Ast.LiteralExpr.class, initializer("const Tuple t=(42);"));
        assertFalse(initializer("const [int] t=(42)+1;") instanceof Ast.TupleExpr);
    }
    @Test void typedGuestConstructionPreservesScalarAndTupleDistinction() {
        String source = program("const [int] t=(42); const x=(42); val int projected=t[0]; val int scalar=x; do if projected eq 42 then stdio.out.write(\"42\"); else stdio.out.write(\"bad-tuple\"); fi do if scalar eq 42 then stdio.out.write(\"42\"); else stdio.out.write(\"bad-scalar\"); fi");
        Ast.Program checked = assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> {
            // Use the existing trusted, exact-source strict compiler-to-Truffle bridge.
            try (OresLanguage.PrecheckedProgramScope lease = OresLanguage.openPrecheckedProgram("intrinsic-singleton.atto", source, checked);
                 Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(out).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID,source,"intrinsic-singleton.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
            }
        });
        assertEquals("4242",out.toString(StandardCharsets.UTF_8));
    }
    @Test void projectionWidthRequiresImmutableLiteralEvidenceNotErasedAnnotation() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(program(
                "const [int] t=(42); val int x=t[0];")));
        for (String body : new String[]{
                "const [int] t=(42); val i32 x=t[0];",
                "const [i32] t=(42); val i32 x=t[0];",
                "let [int] t=(42,); val int x=t[0];"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(program(body)),body);
            assertTrue(error.getMessage().contains("ATTO_STRICT_"),error.getMessage());
        }
    }
    @Test void incompatibleExpectedTypesAndExistingScalarsAreRejected() {
        for (String body : new String[]{
                "const [int,int] t=(42);",
                "const [bool] t=(42);",
                "const [int] t=42;",
                "const x=(42); const [int] t=x;",
                "const [int] t=(42)+1;"
        }) assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(program(body)),body);
    }
}
