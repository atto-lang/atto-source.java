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
                    let String observed_status = "critical-read-failed";
                    self.handle_state(critical[read] |state| -> {
                      if state.revision == 1; do
                        observed_status = "critical-modes-ok";
                      fi
                    });
                    // The output proves the actual loaned value was read,
                    // not just that a callback with a constant message ran.
                    stdio.stdout.write(observed_status);
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
    void nestedCriticalLoanIsNotAnAuthorityEscalation() {
        // A caller cannot use an inner critical block to reacquire the same
        // physical actor state while an outer lexical loan is live.
        String source = actor("""
                    self.handle_state(critical[write] |outer| -> {
                      self.handle_state(critical[read] |inner| -> {
                        let int revision = inner.revision;
                      });
                    });
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source),
                "nested sealed-state loans must fail the source effect/lexical checker");
    }

    @Test
    void criticalReadMustNeverMutateSealedState() {
        String source = actor("""
                    self.handle_state(critical[read] |s| -> {
                      s.revision = 42;
                    });
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("readonly")
                || error.getMessage().contains("read-only"), error.getMessage());
    }

    @Test
    void criticalReadMayNotEscalateToMutableBorrow() {
        String source = actor("""
                    self.handle_state(critical[read] |s| -> {
                      val mutable_alias = &mut s;
                    });
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("shared borrow"),
                error.getMessage());
    }

    @Test
    void unknownCriticalModeRejectedInParser() {
        String source = actor("""
                    self.handle_state(critical[upgrade] |s| -> {
                      val current = s.revision;
                    });
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("critical state access mode"),
                error.getMessage());
    }

    @Test
    void criticalCallbackCannotSuspend() {
        // Both read and write loans are synchronous lexical scopes: permitting
        // a read loan to suspend could retain a physical pin indefinitely.
        for (String mode : java.util.List.of("read", "write")) {
            String source = actor(
                    "    self.handle_state(critical[" + mode + "] |s| -> {\n"
                            + "      await self.get_done();\n"
                            + "    });\n");
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source),
                    "critical[" + mode + "] must not suspend");
            assertNotNull(failure.getMessage());
        }
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
    void criticalReadAndWriteDenyMessagesLifecycleAndHostIoInsideLoan() {
        // Both read and exclusive-write callbacks must remain non-suspending
        // and side-effect bounded. Sending and actor teardown occur only
        // after the state loan is revoked, never inside its callback.
        for (String mode : java.util.List.of("read", "write")) {
            for (String operation : java.util.List.of(
                    "self.send(\"unsafe\");",
                    "self.end();",
                    "stdio.stdout.write(\"unsafe\");")) {
                String source = actor(
                        "    self.handle_state(critical[" + mode + "] |s| -> {\n"
                                + "      " + operation + "\n"
                                + "    });\n");
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheck(source),
                        "critical[" + mode + "] admitted forbidden operation: " + operation);
                assertNotNull(failure.getMessage());
            }
        }
    }

    @Test
    void criticalWriteRejectsUnknownExtraArguments() {
        String source = actor("""
                    self.handle_state(critical[write] |s| -> {
                      s.revision = 1;
                    }, 42);
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void userStringCannotImpersonateInternalWriteSelector() {
        String source = actor("""
                    self.handle_state("critical:write", |s| -> {
                      s.revision = 1;
                    });
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("critical[read]")
                || error.getMessage().contains("inline lexical lambda"), error.getMessage());
    }

    @Test
    void userStringCannotImpersonateInternalReadSelector() {
        String source = actor("""
                    self.handle_state("critical:read", |s| -> {
                      val value = s.revision;
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
