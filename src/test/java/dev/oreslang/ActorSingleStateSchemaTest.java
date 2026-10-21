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
    void handleStateMutatesAndReadsSealedStateAtRuntime() throws Exception {
        String program = """
                define actor StateAuthority as
                  state {
                    revision: int = 0;
                    compacted: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      state.revision = state.revision + 1;
                    });
                    self.handle_state(|state| -> {
                      if state.revision == 1; do
                        self.send("state-loan-ok");
                      else
                        self.send("state-loan-bad");
                      fi
                    });
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val state = spawn StateAuthority();
                  await state.get_ready();
                  state.send("bump");
                  val step = await state.outputs.next();
                  val output = step.value.unwrap();
                  stdio.stdout.write(output.value);
                  await state.get_done();
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("state-loan-ok", run(program, "actor-state-loan-runtime.ores"));
    }

    @Test
    void handleStateRequiresInlineLambda() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(1);
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("inline one-argument lambda"), error.getMessage());
    }

    @Test
    void handleStateRejectsNestedLoans() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      self.handle_state(|inner| -> {
                        inner.revision = inner.revision + 1;
                      });
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("nested self.handle_state"), error.getMessage());
    }

    @Test
    void handleStateRejectsAwaitUntilSuspensionSafeReacquisitionExists() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      await 1;
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("state loan callback cannot await"), error.getMessage());
    }

    @Test
    void handleStateRejectsNonLexicalCallback() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(nlex |state| -> {
                      state.revision = state.revision + 1;
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("callback must be lexical"), error.getMessage());
    }

    @Test
    void handleStateFailsClosedOnImmutableStateSlotsUntilPerSlotLoansExist() {
        String program = """
                define actor Bad as
                  state {
                    val int identity = 1;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      self.send("never");
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("immutable state field"), error.getMessage());
    }

    @Test
    void handleStateBorrowCannotEscapeIntoNestedClosure() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      val callback = || -> {
                        val observed = state.revision;
                      };
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot capture borrowed value 'state'"), error.getMessage());
    }

    @Test
    void handleStateRejectsCooperativeLoopsInM1() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      loop {
                        break;
                      }
                    });
                    self.end();
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot contain cooperative loops"), error.getMessage());
    }

    @Test
    void handleStateRejectsActorTerminationWhileLoanIsLive() {
        String program = """
                define actor Bad as
                  state {
                    revision: int = 0;
                  }
                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      self.end();
                    });
                    return;
                  }
                end
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("cannot terminate the actor"), error.getMessage());
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
