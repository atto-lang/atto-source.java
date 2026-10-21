package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorStateLoanTest {

    @Test
    void handleStateMutatesAndReadsSealedActorState() throws Exception {
        String program = """
                define actor Counter as
                  state {
                    revision: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      state.revision = state.revision + 1;
                    });
                    self.handle_state(|state| -> {
                      if state.revision == 1; do
                        stdio.stdout.write("loan-ok");
                      fi
                    });
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Counter();
                  await worker.get_ready();
                  worker.send("tick");
                  await worker.get_done();
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("loan-ok", run(program, "actor-state-loan.ores"));
    }

    @Test
    void handleStateRequiresInlineLexicalLambda() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(mail.value);
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("inline lexical lambda"), error.getMessage());
    }

    @Test
    void handleStateRejectsNestedClosureEscape() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      const saved = || -> state.revision;
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot suspend"), error.getMessage());
    }

    @Test
    void handleStateRejectsRuntimeIntrinsicsUntilLoanEffectsAreProven() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      rt cooperate;
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot suspend"), error.getMessage());
    }

    @Test
    void handleStateRequiresStateSchema() {
        String program = """
                define actor Bad as
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      return;
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("requires an actor with a state"), error.getMessage());
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
