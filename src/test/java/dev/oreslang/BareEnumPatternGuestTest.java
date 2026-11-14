package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto guest fixture execution, not a host-side enum dispatch model.
 * Full whole-case aliases and named destructuring still need their own
 * typed AST and borrow-proven lowering.
 */
final class BareEnumPatternGuestTest {
    private static String fixture(String name) throws Exception {
        String resource = "/atto/enum-pattern-alias-v1/" + name + ".atto";
        try (InputStream in = BareEnumPatternGuestTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing .atto fixture " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void canonicalNullaryAndPayloadCasesMatchWithoutRedundantOwner() throws Exception {
        String source = fixture("accept-canonical-bare-enum");
        OwnershipChecker.check(TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "enum-bare-pattern.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("41\n5\n0", output.toString(StandardCharsets.UTF_8)
                .trim().replace("\r", ""));
    }

    @Test
    void bareCasesRejectUnknownForeignAndUnreachablePatterns() throws Exception {
        for (String[] invalid : new String[][] {
                {"reject-mixed-duplicate-case", "unreachable match arm"},
                {"reject-foreign-qualified-case", "not valid"},
                {"reject-unknown-bare-case", "not valid"},
                {"reject-nonexhaustive-bare-case", "non-exhaustive match"},
        }) {
            String source = fixture(invalid[0]);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), invalid[0]);
            assertTrue(failure.getMessage().contains(invalid[1]),
                    invalid[0] + " rejected for wrong reason: " + failure.getMessage());
        }
    }
}
