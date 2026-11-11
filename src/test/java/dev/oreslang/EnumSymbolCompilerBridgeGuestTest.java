package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.ReturnCleanupChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto language and Graal guest admission: nullary enum values must
 * remain nominal under construction, equality, return shaping and match.
 *
 * Actor mail's actual local transport is independently exercised by
 * OresEnumSymbolActorMailTest; a cross-unit guest actor fixture requires
 * authenticated enum import metadata and is NOT certified by this class.
 */
final class EnumSymbolCompilerBridgeGuestTest {
    private static String fixture(String filename) throws Exception {
        String resource = "/atto/enum-local-mail/" + filename + ".atto";
        try (InputStream input =
                     EnumSymbolCompilerBridgeGuestTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, "missing " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void realGuestCompilesAndExecutesNullaryNominalSymbols() throws Exception {
        String source = fixture("closed-nullary-symbols");
        Ast.Program parsed = Parser.parse(source);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);
        ReturnCleanupChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, source, "closed-nullary-symbols.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("true\ntrue\ntrue",
                output.toString(StandardCharsets.UTF_8)
                        .strip().replace("\r", ""));
    }

    @Test void actorMayNotCaptureSameFileEnumWithoutAuthenticatedImport()
            throws Exception {
        String source = fixture("actor-ambient-enum-denied");
        assertDoesNotThrow(() -> Parser.parse(source));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
