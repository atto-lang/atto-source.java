package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine source-level integration gate for #656 + #661.
 *
 * One persistent serial actor receives two messages; each receive suspends
 * inside a typed value-producing match and must finish its lexical defer
 * BEFORE publishing the value and admitting the next user message.
 *
 * This proves neither external cancellation unwind nor LLVM parity; the
 * cancellation-vs-Future fault-injection gate remains separate and blocking.
 */
@Timeout(35)
final class SerialTypedValueMatchActorExecutionTest {
    @Test
    void serialSourceReceivesAwaitedTypedMatchAndUnwindsBeforeNextMail()
            throws Exception {
        String source = """
                async fnc ready(): int {
                    rt cooperate;
                    return 7;
                }

                define serial actor Counter as
                    state { count: int = 0; }

                    pub increment(): int using state.read, state.write {
                        return self.handle_state(|s| -> {
                            s.count = s.count + 1;
                            return s.count;
                        });
                    }

                    receive(ActorMail<String> mail): void {
                        val ordinal = self.increment();
                        const answer: int = match<ret=int> await ready() over {
                            on 7 {
                                defer stdio.println("defer-before-result");
                                return ordinal + 7;
                            }
                            default { return 0; }
                        };
                        stdio.println(answer);
                        if (ordinal == 2) {
                            self.end();
                        } fi
                        return;
                    }
                end

                pub async routine main(): void {
                    val worker = spawn Counter();
                    await worker.ready;
                    worker.send("first");
                    worker.send("second");
                    await worker.done;
                    return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source program = Source.newBuilder(OresLanguage.ID, source,
                    "serial-typed-await.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(program));
        }
        assertEquals("defer-before-result\n8\ndefer-before-result\n9\n",
                output.toString(StandardCharsets.UTF_8).replace("\r", ""),
                "every receive must keep its serial lease until ARM_RESULT cleanup completes");
    }
}
