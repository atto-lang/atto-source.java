package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSingleStateSchemaTest {

    @Test
    void sealedStateSchemaParsesTypechecksAndInitializesAtSpawn() throws Exception {
        String program = """
                define actor StateAuthority as
                  state {
                    revision: int = 0;
                    compacted: int = 0;
                    leader_epoch: int = 0;
                    recovered: bool = false;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "shutdown"; do
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val state = spawn StateAuthority();
                  await state.get_ready();
                  state.send("shutdown");
                  await state.get_done();
                  stdio.stdout.write("state-schema-ok");
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("state-schema-ok", run(program, "actor-single-state-schema.ores"));
    }

    @Test
    void duplicateStateSchemaFailsClosed() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  state {
                    epoch: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("only one state"), error.getMessage());
    }

    @Test
    void stateSchemaCannotMixWithFreeStandingActorFields() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  let int escaped;
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("free-standing actor fields"), error.getMessage());
    }

    @Test
    void stateSchemaCannotFollowFreeStandingActorFields() {
        String program = """
                define actor Bad as
                  let int escaped;
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot be mixed"), error.getMessage());
    }

    @Test
    void legacyActorFieldsRemainCompatibleUntilMigration() {
        String program = """
                define actor Legacy as
                  let int started = 1;
                  receive(ActorMail<String> mail): void {
                    if self.started == 1; do
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Legacy();
                  await worker.get_ready();
                  worker.send("stop");
                  await worker.get_done();
                  stdio.stdout.write("legacy-ok");
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertDoesNotThrow(() -> assertEquals(
                "legacy-ok", run(program, "legacy-actor-state-compatibility.ores")));
    }

    @Test
    void directStateReadIsRejectedUntilVerifiedLoanExists() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    if self.revision == 0; do
                      self.end();
                    fi
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("verified critical handle_state"), error.getMessage());
    }

    @Test
    void directStateWriteIsRejectedUntilVerifiedLoanExists() {
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
        assertTrue(error.getMessage().contains("verified critical handle_state"), error.getMessage());
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
