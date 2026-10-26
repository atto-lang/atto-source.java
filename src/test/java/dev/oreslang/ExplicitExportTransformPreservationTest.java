package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitExportTransformPreservationTest {

    @Test
    void annotationExpansionPreservesExportAndPrivateOriginMetadata() {
        Ast.Program parsed = Parser.parse("""
                export class PublicX;

                private define class Hidden as
                end

                define class PublicX as
                end
                """);

        Ast.ModuleDecl before = parsed.modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();

        Ast.ModuleDecl after = AnnotationExpander.expand(parsed).modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();

        assertEquals(before.exports(), after.exports());
        assertEquals(before.explicitlyPrivateSymbols(), after.explicitlyPrivateSymbols());
    }

    @Test
    void explicitDefaultPrivateExportIsLibraryTreeShakerRoot() {
        Ast.Program parsed = Parser.parse("""
                export class Kept;

                define class Kept as
                end

                define class Removed as
                end
                """);

        TreeShaker.Result result = TreeShaker.shake(
                AnnotationExpander.expand(parsed),
                BuildOptions.library(Map.of()));

        assertTrue(result.rootSymbols().contains(Parser.ROOT_MODULE + ".Kept"));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".Kept"));
        assertTrue(result.removed(Parser.ROOT_MODULE + ".Removed"));

        Ast.ModuleDecl root = result.program().modules().stream()
                .filter(module -> module.name().equals(Parser.ROOT_MODULE))
                .findFirst()
                .orElseThrow();
        assertEquals(1, root.exports().size());
        assertEquals("Kept", root.exports().getFirst().items().getFirst().localName());
    }
}
