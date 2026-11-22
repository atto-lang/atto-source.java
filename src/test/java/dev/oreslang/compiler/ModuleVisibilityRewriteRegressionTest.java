package dev.oreslang.compiler;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.ast.StaticProxySynthesis;
import dev.oreslang.parser.Parser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source rewrites must preserve independently checked cross-file export policy.
 * Regression: both transforms previously reset public modules to PRIVATE;
 * annotation expansion also silently erased the file export ledger.
 */
final class ModuleVisibilityRewriteRegressionTest {
    private static final String SOURCE = """
            define pub module exposed as
              define class Widget as
              end
            end
            define module internal as
              fnc secret(): int { return 17; }
            end
            export pub entry;
            define pub fnc entry(): int { return 42; }
            """;

    private static Ast.Program parsed() {
        Ast.Program program = Parser.parse(SOURCE);
        assertEquals(3, program.modules().size(), "two named and one file-root module");
        assertEquals(Ast.Visibility.PUBLIC, program.modules().get(0).visibility());
        assertEquals(Ast.Visibility.PRIVATE, program.modules().get(1).visibility());
        assertEquals(1, program.exports().size(), "explicit export is part of source ABI");
        return program;
    }

    private static void assertPreserved(Ast.Program before, Ast.Program after) {
        assertEquals(before.exports(), after.exports(), "rewrites must retain export declarations");
        assertEquals(before.namespace(), after.namespace());
        assertEquals(before.imports(), after.imports());
        assertEquals(before.modules().size(), after.modules().size());
        for (int index = 0; index < before.modules().size(); index++) {
            Ast.ModuleDecl source = before.modules().get(index);
            Ast.ModuleDecl result = after.modules().get(index);
            assertEquals(source.name(), result.name());
            assertEquals(source.visibility(), result.visibility(),
                    "rewrite must not turn public modules into private modules");
        }
    }

    @Test
    void annotationExpansionPreservesNamedModuleVisibilityAndRootExports() {
        Ast.Program before = parsed();
        Ast.Program rewritten = AnnotationExpander.expand(before);
        assertPreserved(before, rewritten);
    }

    @Test
    void proxySynthesisPreservesPublicModuleAndExplicitExports() {
        Ast.Program before = parsed();
        Ast.Program rewritten = StaticProxySynthesis.expand(before, Set.of("Widget"));
        assertPreserved(before, rewritten);
        assertTrue(rewritten.modules().get(0).declarations().stream()
                .anyMatch(d -> d instanceof Ast.ClassDecl c
                        && c.name().equals("WidgetProxy")), "proxy rewrite must actually run");
    }

    @Test
    void checkedCompilationRetainsPublicModuleImportAfterAnnotationRewrite() {
        String importer = """
                import module exposed from "./library.atto";
                define pub fnc main(): int { return 42; }
                """;
        var graph = assertDoesNotThrow(() -> new IncrementalCompiler().compile(
                Map.of("library.atto", SOURCE, "app.atto", importer)));
        assertEquals(Ast.Visibility.PUBLIC,
                graph.units().get("library.atto").program().modules().getFirst().visibility());
        assertEquals(1, graph.units().get("library.atto").program().exports().size());
    }
}
