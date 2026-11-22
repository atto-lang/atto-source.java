package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java/Truffle guest proof for the explicit v1/v2 callable *scalar*
 * profile. The trusted host binds the exact strict type/ownership-checked AST
 * to an exact source identity using the existing one-shot prechecked lease.
 * This cannot bypass linked import graph validation or untrusted source checks.
 */
final class AttoCallableVersionGuestTest {
    private static String program(String body) {
        return "fnc answer() -> int " + body +
                "\npub fnc main(): void { stdio.stdout.write(answer()); return; }\n";
    }

    private static void executes42(String body, int version, String suffix) throws Exception {
        String sourceText = program(body);
        Ast.Program checked = OresCompiler.parseAndTypeCheckVersionedAtto(sourceText, version);
        String codeUnitId = "version-" + suffix + ".atto";
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram(codeUnitId, sourceText, checked)) {
            context.eval(source);
        }
        assertEquals("42", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actualTruffleGuestExecutesV1UnmarkedCommaTail() throws Exception {
        executes42("1, 42 end", 1, "v1-unmarked");
    }

    @Test
    void actualTruffleGuestExecutesMarkedCommaTailInBothVersions() throws Exception {
        executes42(":: 1, 42 end", 1, "v1-marked");
        executes42(":: 1, 42 end", 2, "v2-marked");
    }

    @Test
    void actualTruffleGuestExecutesV2StatementReturns() throws Exception {
        executes42("return 42; end", 2, "v2-return");
        executes42("^ 42; end", 2, "v2-caret");
    }

    @Test
    void invalidOrUnverifiedBodiesNeverAcquireGuestAdmission() {
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheckVersionedAtto(program("1, 42 end"), 2));
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheckVersionedAtto(program(":: 1; 42 end"), 2));
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheckVersionedAtto(program(":: 1, 42 end"), 3));
        // The single-source admission path cannot silently adopt imported
        // modules whose language-version provenance was not checked.
        assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheckVersionedAtto(
                        "import module external;\n" + program(":: 1, 42 end"), 2));
    }
}
