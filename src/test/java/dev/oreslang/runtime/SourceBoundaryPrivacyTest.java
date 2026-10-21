package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

final class SourceBoundaryPrivacyTest {
    private static final class GuestPanic extends RuntimeException { }

    private static String trace(String codeUnit) {
        var failure = SourceBoundaryTrace.record(
                new IllegalStateException("primary"), codeUnit, "lookup", "source task");
        return failure.getSuppressed()[0].getMessage();
    }

    @Test
    void redactsAbsoluteAndCredentialBearingSourceLocations() {
        assertTrue(trace("/home/developer/private/app/orders.ores").endsWith("[orders.ores]"));
        assertTrue(trace("C:\\Users\\developer\\private\\orders.ores").endsWith("[orders.ores]"));
        assertTrue(trace("file:///private/cache.ores?token=secret").endsWith("[cache.ores]"));
        assertTrue(trace("https://name:pass@example.invalid/source/orders.ores?token=x")
                .endsWith("[orders.ores]"));
        assertTrue(trace("https://name:pass@example.invalid").endsWith("[<remote-source>]"));
        assertTrue(trace("relative/src/orders.ores").endsWith("[relative/src/orders.ores]"));
        assertFalse(trace("/home/developer/private/orders.ores").contains("/home/"));
        assertFalse(trace("https://name:pass@example.invalid/orders.ores").contains("pass@"));
        assertFalse(trace("remote/path.ores?access_token=secret#fragment").contains("secret"));
    }

    @Test
    void oversizedSourcePathsAndUrlsFailClosedWithoutScanningOrEchoingSecrets() {
        String longPath = "/private/".repeat(60_000) + "do-not-leak.ores";
        String longUrl = "https://name:secret@example.invalid/" + "p".repeat(750_000);
        String absoluteTrace = trace(longPath);
        String urlTrace = trace(longUrl);
        assertTrue(absoluteTrace.endsWith("[<source-id-too-long>]"), absoluteTrace);
        assertTrue(urlTrace.endsWith("[<source-id-too-long>]"), urlTrace);
        assertFalse(absoluteTrace.contains("do-not-leak"));
        assertFalse(urlTrace.contains("secret"));

        String atLimit = "a".repeat(4_096);
        String aboveLimit = atLimit + "x";
        assertTrue(trace(atLimit).contains("[" + "a".repeat(159) + "…]"));
        assertTrue(trace(aboveLimit).endsWith("[<source-id-too-long>]"));

        for (int i = 0; i < 100; i++) {
            assertEquals(1, SourceBoundaryTrace.record(
                    new IllegalStateException("preserve"), longUrl, "call", "await")
                    .getSuppressed().length);
        }
    }

    @Test
    void sourceRedactionPreservesEffectClassificationAndTruncation() {
        var failure = new IllegalStateException("primary");
        for (int i = 0; i < 1000; i++) {
            assertSame(failure, SourceBoundaryTrace.record(failure,
                    "/home/developer/private/orders.ores", "receive", "resumed source task"));
        }
        assertEquals(64, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[63].getMessage().contains("truncated"));
        assertTrue(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        failure.addSuppressed(new CancellationException("cleanup cancelled"));
        assertFalse(TrapFailurePolicy.isTrappable(failure, GuestPanic.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(failure, GuestPanic.class));
    }
}
