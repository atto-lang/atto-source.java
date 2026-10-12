package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiled source integration gate for PRs 656 and 661, not a synthetic
 * host-side callback or a stand-in for cancellation cleanup verification.
 *
 * The async value-match case deliberately uses a genuine scheduler handoff
 * inside a serial actor. This test must be run against the combined tree.
 */
@Timeout(30)
final class SourceSerialActorTypedMatchIntegrationTest {
    private static String run(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source compiled = Source.newBuilder(OresLanguage.ID, source,
                "serial-typed-match-integration.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(compiled);
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    @Test
    void serialReceiveEvaluatesTypedResultOnlyAfterLexicalDefer() throws Exception {
        assertEquals("arm-cleanup\n42", run("""
                define serial actor Worker as
                    receive(ActorMail<String> mail): void {
                        const result: int = match<ret=int> 7 over {
                            on 7 {
                                defer stdio.println("arm-cleanup");
                                return 42;
                            }
                            default { return 0; }
                        };
                        stdio.println(result);
                        self.end();
                        return;
                    }
                end
                pub async fnc main(): void {
                    const worker = spawn Worker();
                    await worker.ready;
                    worker.send("go");
                    await worker.done;
                    return;
                }
                """));
    }

    @Test
    void serialReceiveHoldsActorLifetimeThroughAwaitedTypedMatch() throws Exception {
        assertEquals("producer\narm-cleanup\n42", run("""
                async fnc ready(): int {
                    stdio.println("producer");
                    rt cooperate;
                    return 7;
                }
                define serial actor Worker as
                    receive(ActorMail<String> mail): void {
                        const result: int = match<ret=int> await ready() over {
                            on 7 {
                                defer stdio.println("arm-cleanup");
                                return 42;
                            }
                            default { return 0; }
                        };
                        stdio.println(result);
                        self.end();
                        return;
                    }
                end
                pub async fnc main(): void {
                    const worker = spawn Worker();
                    await worker.ready;
                    worker.send("go");
                    await worker.done;
                    return;
                }
                """));
    }

    @Test
    void serialAndSuspendsAreDistinctAndConflictingModifiersStillFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async suspends fnc unsupported(): int { return 1; }
                pub fnc main(): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define serial serial actor Worker as
                    receive(ActorMail<String> mail): void { return; }
                end
                pub fnc main(): void { return; }
                """));
    }
}
