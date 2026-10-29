package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical critical[read]/critical[write] source contract.
 * Actor-owned state loans are synchronous, lexical, void callbacks.
 * Read-mode is immutable; read concurrency is not established by these tests.
 */
final class ActorCriticalStateModeTest {
    private static final String PREFIX = """
            define actor Counter as
              state { revision: int = 0; }
              receive(ActorMail<String> mail): void {
            """;
    private static final String SUFFIX = """
                self.end();
                return;
              }
            end
            """;

    private static String actor(String callback) {
        return PREFIX + callback + SUFFIX;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void criticalWriteThenCriticalReadUsesRealGuestStateLoan() throws Exception {
        String sourceText = actor("""
                    self.handle_state(critical[write] |state| -> {
                      state.revision = 1;
                    });
                    self.handle_state(critical[read] |state| -> {
                      val observed = state.revision;
                    });
                    stdio.stdout.write("critical-modes-ok");
                """) + """
                pub async routine main(): void {
                  val worker = spawn Counter();
                  await worker.get_ready();
                  worker.send("tick");
                  await worker.get_done();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(sourceText));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "critical-modes.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(source);
        }
        assertEquals("critical-modes-ok", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void criticalReadMustNeverMutateSealedState() {
        String source = actor("""
                    self.handle_state(critical[read] |s| -> {
                      s.revision = 42;
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void criticalReadMayNotEscalateToMutableBorrow() {
        String source = actor("""
                    self.handle_state(critical[read] |s| -> {
                      val mutable_alias = &mut s;
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void unknownCriticalModeRejectedInParser() {
        String source = actor("""
                    self.handle_state(critical[upgrade] |s| -> {
                      val current = s.revision;
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void criticalCallbackCannotSuspend() {
        String source = actor("""
                    self.handle_state(critical[write] |s| -> {
                      await self.get_done();
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void callbackMustBeInlineAndVoid() {
        String source = actor("""
                    self.handle_state(critical[write] |s| -> {
                      return 42;
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void directActorStateFieldAccessStillRejected() {
        String source = actor("""
                    self.revision = 42;
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void stateLoanRejectsNestedClosureEscape() {
        String source = actor("""
                    self.handle_state(critical[read] |s| -> {
                      val leaked = || -> s.revision;
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }
}
