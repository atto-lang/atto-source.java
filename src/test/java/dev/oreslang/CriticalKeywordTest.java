package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalKeywordTest {
    @Test void unloweredRwCriticalCannotDowngradeToLegacyWrite() {
        // The runtime has a verified legacy critical WRITE lane, but has not
        // integrated source-level read loans / mode-specific effect proofs.
        // Every adjacent compound spelling must fail before execution.
        String[] unlowered = {
                "pub critical-read fnc peek(): int { return 1; }",
                "pub critical-write fnc put(): int { return 1; }",
                "pub fnc critical-read peek(): int { return 1; }",
                "pub fnc critical-write put(): int { return 1; }",
                "define class Box as pub critical-read get(): int { return 1; } end",
                "define class Box as pub fnc critical-write set(): int { return 1; } end",
                "define actor Box as pub critical-read get(): int { return 1; } end",
                "define actor Box as pub fnc critical-write put(): int { return 1; } end"
        };
        for (String source : unlowered) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("reader/writer critical declaration requires"),
                    () -> source + ": " + error.getMessage());
        }
    }

    @Test void nearMissRwModifiersCannotMisclassifyLegacyCritical() {
        // Exact adjacency is mandatory. In particular comments, whitespace
        // and longer suffix identifiers cannot grant source-level RW effects.
        String[] nearMisses = {
                "pub fnc critical - read peek(): int { return 1; }",
                "pub fnc critical- read peek(): int { return 1; }",
                "pub fnc critical -read peek(): int { return 1; }",
                "pub fnc critical/*comment*/-read peek(): int { return 1; }",
                "pub fnc critical-readonly peek(): int { return 1; }",
                "pub fnc critical--write peek(): int { return 1; }"
        };
        for (String source : nearMisses) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertFalse(error.getMessage().contains("reader/writer critical declaration requires"),
                    () -> "near-miss classified as an RW mode: " + source);
        }
    }

    @Test void criticalModifiersPreserveFileAndInstanceEffects() {
        Ast.Program parsed = Parser.parse("""
                pub critical fnc file_counter(): int { return 1; }
                define actor Foo as
                    suspend critical critical_section(): int { return 3; }
                    receive(ActorMail<String> mail): void { return; }
                end
                """);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        Ast.FunctionDecl file = (Ast.FunctionDecl) root.declarations().get(0);
        Ast.ClassDecl actor = (Ast.ClassDecl) root.declarations().get(1);
        Ast.MethodDecl method = actor.methods().getFirst();
        assertTrue(file.critical());
        assertFalse(file.suspends());
        assertTrue(method.critical());
        assertTrue(method.suspends());
        assertFalse(actor.methods().get(1).critical());
    }

    @Test void criticalIsNotSilentlyAllowedOnDataOrContainers() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical critical fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical generator fnc bad(): int { yield 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical define class Foo as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Foo as critical let int value = 1; end"));
    }

    @Test void criticalDeclarationsCompileAndExecute() throws Exception {
        String code = """
                pub critical fnc file_value(): int { return 23; }
                define class Foo as
                  pub critical answer(): int { return 11; }
                end
                pub fnc main(): void {
                    val foo = new Foo();
                    stdio.stdout.write(foo.answer());
                    stdio.stdout.write("|");
                    stdio.stdout.write(file_value());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-run.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("11|23", stdout.toString().strip());
    }

    @Test void suspendCriticalRetainsEffectAcrossAwaitAndReturnsWithoutHostLocks()
            throws Exception {
        String code = """
                async fnc obtain(): int { return 37; }
                suspend critical fnc guarded(): int {
                    return (await obtain()) + 5;
                }
                async fnc outer(): int { return guarded(); }
                pub fnc main(): void {
                    stdio.stdout.write(await outer());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-suspend.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("42", stdout.toString().strip());
    }

    @Test void criticalCanAppearAfterVisibilityOrBeforeStatic() {
        Ast.Program parsed = Parser.parse("""
                define class Foo as
                    pub critical run(): int { return 1; }
                    critical static fnc value(): int { return 2; }
                end
                """);
        Ast.ClassDecl owner = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertTrue(owner.methods().get(0).critical());
        assertTrue(owner.methods().get(1).critical());
    }

    @Test void uncontendedSuspendCriticalWithNoBodyAwaitStillCompletes() throws Exception {
        // The admission Future is already complete. The SourceTask must yield
        // anyway, resume with its logical gate ticket, and then return.
        String code = """
                pub critical fnc synchronous(): int { return 6; }
                suspend critical fnc guaranteed_handoff(): int { return 7; }
                async fnc outer(): int { return guaranteed_handoff(); }
                pub fnc main(): void {
                    stdio.stdout.write(synchronous());
                    stdio.stdout.write("|");
                    stdio.stdout.write(await outer());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-guaranteed-handoff.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("6|7", stdout.toString().strip());
    }

}
