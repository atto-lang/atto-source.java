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
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual .atto compiler + Graal Context guest actor/mail execution gate. */
@Timeout(25)
final class NominalEnumActorMailGuestTest {
    private static String source(String file) throws Exception {
        String path = "/atto/enum-actor/" + file + ".atto";
        try (InputStream in = NominalEnumActorMailGuestTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing .atto fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void compiledClosedNullaryEnumCrossesRealActorMailboxWithoutSerialization() throws Exception {
        String guest = source("closed-enum-private-mail");
        Ast.Program parsed = Parser.parse(guest);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);
        ReturnCleanupChecker.check(typed);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, guest, "closed-enum-private-mail.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(program);
        }
        assertEquals("LIGHT_ENUM_ACTOR_MAIL_PASS\n",
                stdout.toString(StandardCharsets.UTF_8).replace("\r", ""));
    }

    @Test
    void compiledClosedNullaryEnumCrossesSharedActorMailbox() throws Exception {
        String guest = source("closed-enum-shared-mail");
        Ast.Program parsed = Parser.parse(guest);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);
        ReturnCleanupChecker.check(typed);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, guest, "closed-enum-shared-mail.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(program);
        }
        assertEquals("LIGHT_ENUM_SHARED_MAIL_PASS\\n",
                stdout.toString(StandardCharsets.UTF_8).replace("\\r", ""));
    }

    @Test
    void untrustedActorCannotImportClosedEnumWithoutExportAuthorization() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source("untrusted-enum-mail-rejected"))),
                "untrusted enum symbols need explicit receiver-approved export metadata");
    }

    @Test
    void cannotSendForeignNominalCaseToTypedActorMailbox() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source("foreign-enum-mail-rejected"))),
                "nominal identity must be checked at the compiler send boundary");
    }

    @Test
    void mixedOrPayloadEnumCannotClaimNullarySymbolMailOwnershipProof() throws Exception {
        String guest = source("payload-enum-mail-rejected");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)),
                "payload-bearing enum must be rejected before actor-mail lowering");
    }
}
