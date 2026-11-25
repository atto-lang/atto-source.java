package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real guest checks for heap-trampolined, cancel-safe Map expressions. */
@Timeout(20)
final class PopulatedMapSuspendCancelGuestTest {
    private static String run(String guest) throws Exception {
        OresCompiler.parseAndTypeCheck(guest);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, guest, "map-suspend-cancel.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void asyncKeysAndSuspendsValuesResumeInSourceOrderExactlyOnce() throws Exception {
        assertEquals("K|V|T|3", run("""
                async fnc async_key(): string {
                    stdio.stdout.write("K|");
                    rt cooperate;
                    return "first";
                }
                suspends fnc suspends_value(): int {
                    stdio.stdout.write("V|");
                    rt cooperate;
                    return 11;
                }
                async fnc final_value(): int {
                    stdio.stdout.write("T|");
                    rt cooperate;
                    return 3;
                }
                pub async fnc main(): void {
                    const map = ~map<string, int>(
                        await async_key() => suspends_value(),
                        "second" => 2,
                        "third" => await final_value()
                    );
                    stdio.stdout.write(map.size);
                    return;
                }
                """));
    }

    @Test
    void largeMapWithFinalAwaitDoesNotRecursivelyExhaustCarrierStack() throws Exception {
        StringBuilder guest = new StringBuilder("""
                async fnc final_value(): int {
                    rt cooperate;
                    return 7;
                }
                pub async fnc main(): void {
                    const map = ~map<string, int>(
                """);
        for (int i = 0; i < 1024; i++) {
            guest.append("  \"k").append(i).append("\" => ").append(i).append(",\n");
        }
        guest.append("""
                        "tail" => await final_value()
                    );
                    stdio.stdout.write(map.size);
                    return;
                }
                """);
        assertEquals("1025", run(guest.toString()));
    }

    @Test
    void cancellationDuringSuspendingMapEntryDoesNotPublishIt() throws Exception {
        assertEquals("cleanup|#cancelled", run("""
                define class Flag as
                  pub let int started = 0;
                end
                suspends fnc blocked_value(SharedMutex<Flag> signal): int {
                    block {
                        val g = signal.lock();
                        g.started = 1;
                    }
                    loop { rt cooperate; }
                    return 42;
                }
                pub async fnc main(): void {
                    val signal = SharedMutex.new(new Flag());
                    let Future<void> child = task {
                        defer stdio.stdout.write("cleanup|");
                        const map = ~map<string, int>(
                            "initial" => 1,
                            "pending" => blocked_value(signal)
                        );
                        stdio.stdout.write("published");
                        return;
                    };
                    let mut int seen = 0;
                    while seen == 0 {
                        rt cooperate;
                        block {
                            val g = signal.lock();
                            seen = g.started;
                        }
                    }
                    child.cancel();
                    stdio.stdout.write(await child.get_done());
                    return;
                }
                """));
    }

    @Test
    void activeBorrowAcrossMapAwaitIsRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub let int item = 7;
                end
                async fnc next_value(): int {
                    rt cooperate;
                    return 3;
                }
                pub async fnc main(): void {
                    let Box owner = new Box();
                    val loan = rt borrow owner;
                    const map = ~map<string, int>(
                        "first" => 1,
                        "suspended" => await next_value()
                    );
                    stdio.stdout.write(loan.item);
                    return;
                }
                """));
    }

    @Test
    void mapKeyAndValueCapturesAreCheckedForMoveAfterSpawn() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub let int value = 7;
                end
                pub async fnc main(): void {
                    let Box owner = new Box();
                    let Future<void> child = task {
                        const map = ~map<string, int>(
                            "item" => owner.value
                        );
                        return;
                    };
                    stdio.stdout.write(owner.value);
                    await child;
                    return;
                }
                """));
    }

    @Test
    void exceedingMapEntryQuotaFailsBeforeAnyGuestEffect() {
        StringBuilder guest = new StringBuilder("""
                fnc side_effect(): int {
                    stdio.stdout.write("EXECUTED");
                    return 1;
                }
                pub fnc main(): void {
                    const map = ~map<string, int>(
                """);
        guest.append("\"first\" => side_effect(),\n");
        for (int i = 0; i < dev.oreslang.runtime.OresKeyedMap.MAX_ENTRIES; i++) {
            guest.append("  \"key").append(i).append("\" => ").append(i);
            if (i + 1 < dev.oreslang.runtime.OresKeyedMap.MAX_ENTRIES) {
                guest.append(",\n");
            }
        }
        guest.append("""
                    );
                    stdio.stdout.write(map.size);
                    return;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(guest.toString()));
    }
}
