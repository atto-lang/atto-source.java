package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combined critical + pub/sub source admission and Polyglot guest execution.
 * No method is proof of backend parity unless a source-org exact-head
 * nonzero-step workflow executes it, and the runtime output is verified.
 */
@Timeout(60)
final class CriticalPubSubIntegrationGuestTest {
    private static final String STEM = "critical-pubsub-integration-v1";

    @Test
    void integrationSourceIsByteIdenticalAcrossAttoAndOres() throws Exception {
        assertEquals(source("atto"), source("ores"));
        String contents = source("atto");
        assertTrue(contents.contains("define actor CriticalWorker as"));
        assertTrue(contents.contains("define actor Subscriber as"));
        assertTrue(contents.contains("critical {"));
        assertTrue(contents.contains("pub suspends fnc main(): void {"));
        assertFalse(contents.contains("pub async routine main()"));
    }

    @Test
    void integrationReferencesPinnedCriticalAndPubsubSources() throws Exception {
        assertEquals("50a62a32495989366624145c08c832e38c2c1a78",
                gitBlob(Path.of("examples", "critical-actor-checked-v1.atto")));
        assertEquals("fe10d9e5018a3f030a10bc31586e851198aaa4a3",
                gitBlob(Path.of("contracts", "v1", "fixtures",
                        "actor-pubsub-formal-suspends-v1.atto")));
    }

    @Test
    void combinedCriticalPubsubGuestRunsOnActualGraalRuntime() throws Exception {
        String program = source("atto");
        // Invoke the real guest compiler admission, then Graal Polyglot
        // evaluation. This is not a host-side simulation of actor semantics.
        OresCompiler.parseAndTypeCheck(program);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, STEM + ".atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("subscribed|p-1|42", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sourceTaskTopicCapabilityIsRejectedAfterSuccessfulParsing() throws Exception {
        String illegal = Files.readString(Path.of("contracts", "v1", "fixtures",
                "actor-pubsub-reject-suspends-capability.atto"));
        Ast.Program parsed = Parser.parse(illegal);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        assertTrue(fn.suspends());
        assertFalse(fn.async());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(illegal));
        assertTrue(denied.getMessage().contains("must be concrete owned task-safe data"),
                denied::toString);
        assertFalse(denied.getMessage().contains("'async' callable modifier is removed"),
                "an obsolete syntax error is not a Send violation");
    }

    @Test
    void forbiddenCriticalSuspendsModifierCombinationRemainsRejected() {
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical suspends fnc unsafe(): void { return; }"));
        assertTrue(denied.getMessage().contains("E_CRITICAL_SUSPENDS_DECL"),
                denied::toString);
    }

    private static String source(String extension) throws Exception {
        return Files.readString(Path.of("contracts", "v1", "fixtures",
                STEM + "." + extension), StandardCharsets.UTF_8);
    }

    private static String gitBlob(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }
}
