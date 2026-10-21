package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OrestraStatefulBootstrapTest {
    private static final String FIXTURE_BLOB =
            "e9acf0bcbd82e1a1d8a05a3047aa0f9df3baf3bb";

    @Test
    void reservationStatePersistsAcrossMailboxTurns() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/orestra/stateful-bootstrap.ores")) {
            assertNotNull(input, "stateful Orestra fixture missing");
            bytes = input.readAllBytes();
        }
        assertEquals(FIXTURE_BLOB, gitBlobSha(bytes),
                "stateful Orestra fixture drifted from reviewed Git blob");

        String program = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(program.contains("state {"));
        assertTrue(program.contains("self.handle_state(|s| -> {"));
        assertTrue(program.contains("await state.get_ready()"));
        assertTrue(program.contains("await state.get_done()"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "orestra-stateful-bootstrap.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            assertDoesNotThrow(() -> context.eval(source));
        }

        assertEquals(
                "reserve-ok\naccumulate-ok\ncapacity-rejected\nrelease-ok\nrelease-rejected\nfinal-ok\n",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void directStateBypassFailsClosed() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.revision = 1;
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("verified critical handle_state"),
                error.getMessage());
    }

    @Test
    void stateLoanCannotCrossSuspensionOrTerminateActor() {
        String suspends = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|s| -> {
                      s.revision = s.revision + 1;
                      await 1;
                    });
                    self.end();
                    return;
                  }
                end
                """;
        IllegalArgumentException suspendError = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(suspends));
        assertTrue(suspendError.getMessage().contains(
                "state loan callback cannot await"), suspendError.getMessage());

        String terminates = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|s| -> {
                      s.revision = s.revision + 1;
                      self.end();
                    });
                    return;
                  }
                end
                """;
        IllegalArgumentException terminationError = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(terminates));
        assertTrue(terminationError.getMessage().contains(
                "cannot terminate the actor"), terminationError.getMessage());
    }

    private static String gitBlobSha(byte[] content) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + content.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        sha1.update(content);
        return HexFormat.of().formatHex(sha1.digest());
    }
}
