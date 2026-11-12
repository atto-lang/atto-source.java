package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Bare handle_state must enter exactly the existing sealed self.handle_state checks. */
final class ActorBareHandleStateCallTest {
    private static String actor(String body) {
        return """
                define actor Counter as
                  state { revision: int = 0; }
                  receive(ActorMail<String> mail): void {
                """ + body + """
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
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void bareWriteAndExplicitReadShareOneCheckedActorState() throws Exception {
        String source = actor("""
                  handle_state(critical[write] |state| -> {
                    state.revision = 7;
                  });
                  let String verdict = "bad";
                  self.handle_state(critical[read] |state| -> {
                    if state.revision == 7; do
                      verdict = "bare-state-loan-ok";
                    fi
                  });
                  stdio.stdout.write(verdict);
                """);
        assertEquals("bare-state-loan-ok", run(source));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void explicitWriteAndBareReadShareOneCheckedActorState() throws Exception {
        String source = actor("""
                  self.handle_state(critical[write] |state| -> {
                    state.revision = 9;
                  });
                  let String verdict = "bad";
                  handle_state(critical[read] |state| -> {
                    if state.revision == 9; do
                      verdict = "explicit-state-loan-ok";
                    fi
                  });
                  stdio.stdout.write(verdict);
                """);
        assertEquals("explicit-state-loan-ok", run(source));
    }

    @Test
    void bareReadCannotWriteEvenInsideActor() {
        String source = actor("""
                  handle_state(critical[read] |state| -> {
                    state.revision = 42;
                  });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void bareStateLoanCannotSuspend() {
        String source = actor("""
                  handle_state(critical[write] |state| -> {
                    rt cooperate;
                  });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void unknownModeIsRejectedBeforeAnyRuntimeAdmission() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(actor("""
                  handle_state(critical[upgrade] |state| -> {
                    state.revision = 1;
                  });
                """)));
    }

    @Test
    void bareStateLoanInFreeFunctionHasNoActorAuthority() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc probe(): void {
                          handle_state(critical[write] |state| -> {
                            state.revision = 1;
                          });
                          return;
                        }
                        """)));
    }

    @Test
    void lexicalShadowCannotEscalateIntoSealedIntrinsic() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(actor("""
                  val handle_state = 1;
                  handle_state(critical[write] |state| -> {
                    state.revision = 1;
                  });
                """)));
    }

    @Test
    void bareNameNeverCapturesASealedActorStateLoanValue() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(actor("""
                  val fn = handle_state;
                """)));
    }

    @Test
    void userDefinedActorHandleStateIsStillForbidden() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Counter as
                          state { revision: int = 0; }
                          pub handle_state(): void { return; }
                        end
                        """));
    }

    @Test
    void valueReturningCallbackIsStillNotImplementedAndFailsClosed() {
        // The requested WAL API needs a bool result. This draft's physical
        // loan runtime admits only synchronous void callbacks, so do not
        // silently relax its contract.
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(actor("""
                  handle_state(critical[write] |state| -> bool {
                    return true;
                  });
                """)));
    }

    private static String run(String program) throws Exception {
        OresCompiler.parseAndTypeCheck(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "bare-handle-state.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
