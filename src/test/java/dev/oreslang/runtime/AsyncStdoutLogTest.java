package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncStdoutLogTest {
    @Test void orderedFlushAndImplicitShutdownDrain() throws Exception {
        StringWriter output = new StringWriter();
        AsyncStdoutLog logs = new AsyncStdoutLog(new PrintWriter(output), 16);
        logs.logAsync("first");
        logs.logAsync("second");
        logs.flushAsync().get(3, TimeUnit.SECONDS);
        assertEquals("first" + System.lineSeparator() + "second" + System.lineSeparator(), output.toString());
        logs.logAsync("third");
        logs.close(Duration.ofSeconds(3));
        assertEquals("first" + System.lineSeparator() + "second" + System.lineSeparator()
                + "third" + System.lineSeparator(), output.toString());
        assertThrows(IllegalStateException.class, () -> logs.logAsync("late"));
    }

    @Test void flushBarrierDoesNotBlockCallerOnWriter() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var backing = new java.io.Writer() {
            @Override public void write(char[] c, int off, int len) {
                entered.complete(null);
                release.join();
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        AsyncStdoutLog logs = new AsyncStdoutLog(new PrintWriter(backing), 16);
        try {
            logs.logAsync("slow");
            entered.get(2, TimeUnit.SECONDS);
            CompletableFuture<Void> barrier = logs.flushAsync();
            assertFalse(barrier.isDone(), "flush must wait for prior pending stdout writes");
            release.complete(null);
            barrier.get(3, TimeUnit.SECONDS);
        } finally {
            release.complete(null);
            logs.close(Duration.ofSeconds(3));
        }
    }

    @Test void zeroCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AsyncStdoutLog(new PrintWriter(new StringWriter()), 0));
    }
}
