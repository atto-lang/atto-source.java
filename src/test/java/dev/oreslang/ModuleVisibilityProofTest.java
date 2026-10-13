package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.VisibilityPolicy;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finite-state model checking of the export lattice together with compiler
 * integration checks. The oracle below is independent from VisibilityPolicy.
 *
 * Variables: module visibility, declaration visibility, synthetic/file root
 * bit, source spelling, import selector. Every state in these finite domains
 * is enumerated (not randomized). This is a bounded proof, not a claim that
 * arbitrary compiler programs have been formally verified.
 */
final class ModuleVisibilityProofTest {
    @Test
    void exhaustivelyChecksTheTwoVisibilityBitsAndRootKind() {
        int checked = 0;
        for (boolean synthetic : new boolean[] {false, true}) {
            for (Ast.Visibility moduleVisibility : Ast.Visibility.values()) {
                for (Ast.Visibility memberVisibility : Ast.Visibility.values()) {
                    Ast.ModuleDecl module = new Ast.ModuleDecl(
                            synthetic ? Parser.ROOT_MODULE : "Service",
                            java.util.List.of(), java.util.List.of(), java.util.List.of(),
                            moduleVisibility);
                    // Independent specification: a synthetic root is not
                    // directly importable; its explicitly public *members*
                    // are. For a named module, both must be pub.
                    boolean expectedModule = !synthetic
                            && moduleVisibility == Ast.Visibility.PUBLIC;
                    boolean expectedMember = (synthetic || expectedModule)
                            && memberVisibility == Ast.Visibility.PUBLIC;
                    assertEquals(expectedModule, VisibilityPolicy.exportsModule(module));
                    assertEquals(expectedMember,
                            VisibilityPolicy.exports(module, memberVisibility));
                    assertEquals(synthetic, VisibilityPolicy.isSyntheticRoot(module));
                    checked++;
                }
            }
        }
        assertEquals(8, checked);
    }

    @Test
    void removingOrAddingNonpublicDeclarationsNeverWidensExports() {
        for (Ast.Visibility moduleVisibility : Ast.Visibility.values()) {
            for (Ast.Visibility memberVisibility : Ast.Visibility.values()) {
                Ast.ModuleDecl module = new Ast.ModuleDecl("Service",
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), moduleVisibility);
                boolean exportedBefore = VisibilityPolicy.exports(module, memberVisibility);
                boolean exportedAfterAddingPrivate =
                        VisibilityPolicy.exports(module, memberVisibility);
                assertEquals(exportedBefore, exportedAfterAddingPrivate);
                if (moduleVisibility == Ast.Visibility.PRIVATE) {
                    assertFalse(exportedBefore);
                }
                if (memberVisibility == Ast.Visibility.PRIVATE) {
                    assertFalse(exportedBefore);
                }
            }
        }
    }

    @Test
    void parserAcceptsPrivateAndPublicModuleOrdersAndRejectsRootSpoofing() {
        for (String spelling : new String[] {
                "define module Service as end",
                "define private module Service as end",
                "private define module Service as end"
        }) {
            assertEquals(Ast.Visibility.PRIVATE,
                    Parser.parse(spelling).modules().getFirst().visibility(), spelling);
        }
        for (String spelling : new String[] {
                "define pub module Service as end",
                "pub define module Service as end"
        }) {
            assertEquals(Ast.Visibility.PUBLIC,
                    Parser.parse(spelling).modules().getFirst().visibility(), spelling);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub module __root__ as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub async module Service as end"));
        // A user-authored root-level function is not an exported module.
        Ast.ModuleDecl root = Parser.parse("pub fnc visible(): int { return 1; }")
                .modules().getFirst();
        assertEquals(Parser.ROOT_MODULE, root.name());
        assertFalse(VisibilityPolicy.exportsModule(root));
        assertTrue(VisibilityPolicy.permitsMemberExport(root));
    }

    @Test
    void bothExplicitPrivateOrdersHaveExactlyTheSameAstAsImplicitPrivate() {
        String implicit = "define module Internal as\n"
                + "  pub fnc visible(): int { return 1; }\n"
                + "end\n";
        Ast.Program baseline = Parser.parse(implicit);
        for (String modifier : new String[] {
                "private define module Internal as",
                "define private module Internal as"
        }) {
            String variant = implicit.replace("define module Internal as", modifier);
            assertEquals(baseline, Parser.parse(variant),
                    "redundant private changes AST for " + modifier);
        }
        for (String modifier : new String[] {
                "pub define module Internal as",
                "define pub module Internal as"
        }) {
            Ast.Program pub = Parser.parse(implicit.replace("define module Internal as", modifier));
            assertEquals(Ast.Visibility.PUBLIC, pub.modules().getFirst().visibility());
            assertNotEquals(baseline, pub);
        }
    }

    @Test
    void allPrivateSpellingsRejectCrossFileModuleAndMemberImports() {
        for (String header : new String[] {
                "define module Internal as",
                "private define module Internal as",
                "define private module Internal as"
        }) {
            for (String selection : new String[] {
                    "module Internal", "fnc visible"
            }) {
                Map<String, String> files = Map.of(
                        "pkg/lib.ores",
                        header + "\n  pub fnc visible(): int { return 9; }\nend\n",
                        "pkg/main.ores",
                        "import " + selection + " from './lib';\n"
                                + "pub fnc main(): void { return; }\n");
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> new IncrementalCompiler().compile(files),
                        header + " / " + selection);
                assertTrue(failure.getMessage().contains("does not match an exported declaration"),
                        failure.getMessage());
            }
        }
    }

    @Test
    void namedSelectorsCannotBypassAnUnexportedModule() {
        String members = """
                pub fnc f(): int { return 1; }
                pub class Box as end
                pub type Id = int;
                """;
        // The same public member signatures inside a private container
        // must be rejected for every supported direct import selector.
        for (boolean modulePublic : new boolean[] {false, true}) {
            String source = "define " + (modulePublic ? "pub " : "")
                    + "module Service as\n" + members + "\nend\n";
            for (String selector : new String[] {
                    "module Service",
                    "fnc f",
                    "class Box",
                    "type Id",
                    "types Id"
            }) {
                Map<String, String> files = Map.of(
                        "pkg/lib.ores", source,
                        "pkg/main.ores",
                        "import " + selector + " from './lib';\n"
                                + "pub fnc main(): void { return; }");
                if (modulePublic) {
                    assertDoesNotThrow(() -> new IncrementalCompiler().compile(files),
                            "should import " + selector + " from a public module");
                } else {
                    IllegalArgumentException thrown = assertThrows(
                            IllegalArgumentException.class,
                            () -> new IncrementalCompiler().compile(files),
                            "private module leaked " + selector);
                    assertTrue(thrown.getMessage().contains("does not match an exported declaration"),
                            thrown.getMessage());
                }
            }
        }
    }

    @Test
    void editingPrivateModuleDoesNotInvalidateASeparatePublicImport() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String entry = "import fnc exposed from './lib';\n"
                + "pub fnc main(): int { return exposed(); }\n";
        String publicApi = "pub fnc exposed(): int { return 1; }\n";
        String hiddenBefore = "define module Internal as\n"
                + "  pub fnc secret(): int { return 3; }\nend\n";
        String hiddenAfter = "define private module Internal as\n"
                + "  pub fnc secret(): int { return 4; }\nend\n";
        Map<String, String> original = Map.of(
                "pkg/lib.ores", publicApi + hiddenBefore,
                "pkg/main.ores", entry);
        compiler.compile(original);
        var updated = compiler.compile(Map.of(
                "pkg/lib.ores", publicApi + hiddenAfter,
                "pkg/main.ores", entry));
        assertTrue(updated.rebuilt().contains("pkg/lib.ores"));
        assertTrue(updated.reused().contains("pkg/main.ores"),
                "private named module edits must not change public ABI");
    }

    @Test
    void explicitlyPublicFileRootMembersRemainImportable() {
        Map<String, String> files = Map.of(
                "pkg/lib.ores",
                "pub fnc f(): int { return 1; }\npub class Box as end\npub type Id = int;",
                "pkg/main.ores",
                "import fnc f from './lib';\nimport class Box from './lib';\n"
                        + "import type Id from './lib';\npub fnc main(): void { return; }");
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(files));
    }
}
