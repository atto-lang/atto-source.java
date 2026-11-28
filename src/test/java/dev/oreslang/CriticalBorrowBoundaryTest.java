package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A logical critical ticket never grants permission to suspend a live borrow. */
final class CriticalBorrowBoundaryTest {
    private static final String PREFIX = """
            define class Box as
                pub let String value = "owned";
            end
            @Critical(channel_wait_timeout_ms=100)
            pub critical suspends fnc guarded(): void {
                val Channel<int> ch = Channel.new<int>(1);
                let Box target = new Box();
                val &Box view = rt borrow target;
            """;
    private static final String SUFFIX = """
                stdio.println(view.value);
                return;
            }
            """;

    private static void rejectsBorrow(String operation, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(PREFIX + operation + SUFFIX));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test void criticalReadStillRejectsLiveBorrow() {
        rejectsBorrow("val int received = readch ch;\n",
                "cannot readch/writech while an ordinary borrow is live");
    }

    @Test void criticalWriteStillRejectsLiveBorrow() {
        rejectsBorrow("writech ch, 12;\n",
                "cannot readch/writech while an ordinary borrow is live");
    }

    @Test void inlineOwnedChannelAwaitStillRejectsLiveBorrow() {
        rejectsBorrow("val int received = await (nb readch ch);\n",
                "cannot await while an ordinary borrow is live");
    }

    @Test void ownedValuesPermitCriticalChannelSuspension() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                @Critical(channel_wait_timeout_ms=100)
                pub critical suspends fnc guarded(): int {
                    val Channel<int> ch = Channel.new<int>(1);
                    writech ch, 12;
                    return await (nb readch ch);
                }
                """));
    }
}
