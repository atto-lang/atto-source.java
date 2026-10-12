package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deliberately crosses two previously independent branches: a serial source
 * actor holds its mailbox lease across an awaited value-producing match, and
 * ARM_RESULT must run LIFO cleanup before the next receive is admitted.
 *
 * This regression must pass on the merged source tree, not on either PR alone.
 */
@Timeout(30)
final class SerialActorTypedMatchIntegrationTest {
    @Test
    void sourceReceiveKeepsOneTicketUntilAwaitedMatchCleanupFinishes() throws Exception {
        String guest = """
            define serial actor Counter as
                state { count: int = 0; }

                static async fnc produce_value(): int {
                    stdio.println("producer");
                    rt cooperate;
                    return 7;
                }

                pub increment(): int using state.read, state.write {
                    return self.handle_state(|s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }

                suspends receive(ActorMail<String> mail): void {
                    const answer: int = match<ret=int> await Counter.produce_value() over {
                        on 7 {
                            defer stdio.println("first");
                            defer stdio.println("last");
                            return 42;
                        }
                        default { return 0; }
                    };
                    stdio.println(answer);
                    val number = self.increment();
                    stdio.println(number);
                    if (number == 2) {
                        self.end();
                    } fi
                    return;
                }
            end

            pub async routine main(): void {
                val worker = spawn Counter();
                await worker.get_ready();
                worker.send("first");
                worker.send("second");
                await worker.get_done();
                return;
            }
            """;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source compiled = Source.newBuilder(
                OresLanguage.ID, guest, "serial-typed-match-await.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            assertDoesNotThrow(() -> context.eval(compiled));
        }
        assertEquals(
                "producer\nlast\nfirst\n42\n1\nproducer\nlast\nfirst\n42\n2\n",
                out.toString(StandardCharsets.UTF_8),
                "the next message must not enter before awaited ARM_RESULT cleanup and publication");
    }
    @Test
    void shadowedActorTypeCannotAuthorizeAwaitedStaticMatchProducer() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck("""
                    define serial actor Counter as
                        static async fnc produce_value(): int {
                            return 7;
                        }
                        suspends receive(ActorMail<String> mail): void {
                            const Counter: int = 1;
                            const answer: int = match<ret=int> await Counter.produce_value() over {
                                default { return 42; }
                            };
                            stdio.println(answer);
                            return;
                        }
                    end
                    """));
        assertNotNull(rejection.getMessage());
    }

}
