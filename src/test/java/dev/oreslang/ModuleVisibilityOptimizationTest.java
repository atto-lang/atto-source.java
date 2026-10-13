package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ModuleVisibilityOptimizationTest {
    @Test
    void annotationExpansionPreservesExplicitPublicAndImplicitOrExplicitPrivate() {
        for (String header : new String[] {
                "define pub module Visible as",
                "pub define module Visible as",
                "define module Visible as",
                "define private module Visible as",
                "private define module Visible as"
        }) {
            Ast.Program source = Parser.parse(header + """
                    
                      pub fnc answer(): int { return 42; }
                    end
                    """);
            Ast.Program expanded = AnnotationExpander.expand(source);
            assertEquals(source.modules().getFirst().visibility(),
                    expanded.modules().getFirst().visibility(), header);
            assertEquals(source.modules().getFirst().contracts(),
                    expanded.modules().getFirst().contracts(), header);
        }
    }

    @Test
    void treeShakerTreatsPublicMembersOfPrivateModulesAsPrivateAndKeepsModuleVisibility() {
        TreeShaker.Result result = OresCompiler.compileForBuild("""
                define module Internal as
                  pub fnc seemingly_public(): int { return 1; }
                  pub type InternalAlias = int;
                end

                define pub module PublicAPI as
                  pub fnc exported(): int { return 2; }
                  fnc helper(): int { return 3; }
                end

                pub fnc root_export(): int { return 4; }
                type PrivateAlias = int;
                pub type PublicAlias = int;
                """, BuildOptions.library(Map.of()));

        assertTrue(result.retained("PublicAPI.exported"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".root_export"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".PublicAlias"));
        assertTrue(result.removed("Internal.seemingly_public"));
        assertTrue(result.removed("Internal.InternalAlias"));
        assertTrue(result.removed("PublicAPI.helper"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".PrivateAlias"));

        Ast.ModuleDecl exported = result.program().modules().stream()
                .filter(module -> module.name().equals("PublicAPI"))
                .findFirst().orElseThrow();
        assertEquals(Ast.Visibility.PUBLIC, exported.visibility());
        assertTrue(result.program().modules().stream()
                .noneMatch(module -> module.name().equals("Internal")));
    }
}
