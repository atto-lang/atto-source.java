package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-time regression: every *blocking* suspension must obey await's
 * lexical borrow lifetime, irrespective of which source syntax emits it.
 */
final class BlockingSuspensionBorrowLifetimeTest {

    private static IllegalArgumentException rejected(String operation) {
        String source = """
                fnc main(Channel<int> channel): void {
                  val array = arr[1, 2, 3];
                  val borrowed = rt borrow array;
                """ + operation + """
                  return;
                }
                """;
        return assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void awaitAndBlockingChannelReadRejectLiveBorrow() {
        var read = rejected("  val value = readch channel;\n");
        assertTrue(read.getMessage().contains("readch"));
        assertTrue(read.getMessage().contains("borrow"));

        var await = rejected("  val value = await Future.completed(1);\n");
        assertTrue(await.getMessage().contains("await"));
        assertTrue(await.getMessage().contains("borrow"));
    }

    @Test
    void blockingChannelWriteRejectsLiveBorrow() {
        var error = rejected("  writech channel, 42;\n");
        assertTrue(error.getMessage().contains("writech"));
        assertTrue(error.getMessage().contains("borrow"));
    }

    @Test
    void blockingStaticSelectRejectsLiveBorrow() {
        var error = rejected("""
                  do select {
                    when readch channel: const value {
                      return;
                    }
                  }
                """);
        assertTrue(error.getMessage().contains("select"));
        assertTrue(error.getMessage().contains("borrow"));
    }

    @Test
    void immediateChannelProbeDoesNotSuspendOwnerFrame() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                fnc main(Channel<int> channel): void {
                  val array = arr[1, 2, 3];
                  val borrowed = rt borrow array;
                  val result = try readch channel;
                  return;
                }
                """)));
    }
}
