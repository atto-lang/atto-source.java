package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nullary nominal enum aliases go through the real type/ownership checker
 * and guest evaluator. Non-Copy payloads may only be borrowed within a match arm;\n * they must never be returned as owned values.
 */
final class NullaryEnumCaseAliasGuestTest {
    private static String fixture(String name) throws Exception {
        String path = "/atto/enum-pattern-alias-v1/" + name + ".atto";
        try (InputStream in = NullaryEnumCaseAliasGuestTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing .atto guest fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aliasesAreScopedAndCopyOnlyNullaryCasesExecute() throws Exception {
        String program = fixture("accept-nullary-alias");
        OwnershipChecker.check(TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, "nullary-enum-alias.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("7\n9", output.toString(StandardCharsets.UTF_8)
                .trim().replace("\r", ""));
    }

    @Test
    void nonCopyWholeAliasCannotBeReturnedAsOwnedMessage() throws Exception {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(
                        TypeChecker.check(Parser.parse(fixture("reject-payload-whole-alias")))));
        assertNotNull(rejected.getMessage());
    }

    @Test
    void foreignNominalAliasCaseIsRejectedBeforeGuestExecution() throws Exception {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture("reject-foreign-whole-alias"))));
        assertTrue(rejected.getMessage().contains("not valid"), rejected.getMessage());
    }
}
