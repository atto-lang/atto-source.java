package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ChannelStdlibAttoSourceTest {
    private static final Path FIXTURES = Path.of("src/test/resources/atto/std-channel");

    @Test
    void packagedSourceIsAttoAndHasStableImports() throws Exception {
        assertTrue(CoreLibrarySources.packagedSourcePaths().contains("channel.atto"));
        String canonical = CoreLibrarySources.source("std/channel");
        assertEquals(canonical, CoreLibrarySources.source("ores.std/channel"));
        assertTrue(canonical.startsWith("// AttoLang std/channel"));
        Parser.parse(canonical);
    }

    @Test
    void boundedAsyncSendReceivePreservesFifoAndDrainsBeforeClose() throws Exception {
        assertEquals("1020C", run("buffered_fifo.atto"));
    }

    @Test
    void zeroCapacityRendezvousCompletesBothRegisteredFutures() throws Exception {
        assertEquals("42", run("rendezvous_pending.atto"));
    }

    @Test
    void immediateFullProbeReturnsRejectedPayloadWithoutRegistering() throws Exception {
        assertEquals("accepted:full:2:1", run("immediate_probe.atto"));
    }

    @Test
    void ownedArrayPayloadMovesThroughGenericAttoFunctionBoundary() throws Exception {
        assertEquals("accepted:56", run("owned_payload.atto"));
    }

    @Test
    void negativeCapacityFailsClosedWithoutCreatingChannel() throws Exception {
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run("negative_capacity.atto"));
        assertTrue(failure.isGuestException(), "expected a guest-level validation error");
        assertTrue(failure.getMessage().contains("std/channel: capacity must be nonnegative"),
                failure::getMessage);
    }

    private static String run(String file) throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve(file));
        String program = new String(bytes, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, file)
                .mimeType(OresLanguage.MIME_TYPE).build();
        Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build();
        try {
            context.eval(source);
        } finally {
            context.close(true);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
