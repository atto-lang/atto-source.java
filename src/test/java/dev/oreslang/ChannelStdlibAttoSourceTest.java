package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.parser.Parser;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
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
        assertEquals(canonical, CoreLibrarySources.source("std/channel.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/channel.ores"));
        assertThrows(IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/../channel"));
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
    void directAwaitedReceiveRendezvousesWithPendingWriterAtZeroCapacity() throws Exception {
        assertEquals("42", run("rendezvous_direct_read.atto"));
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
    void closingTwicePreservesCommittedFifoValues() throws Exception {
        assertEquals("34:closed", run("close_drain.atto"));
    }

    @Test
    void immediateReceiveDistinguishesEmptyAndReadyWithoutDropping() throws Exception {
        assertEquals("empty:7", run("try_read.atto"));
    }

    @Test
    void closedImmediateSendReturnsOriginalOwnedArray() throws Exception {
        assertEquals("closed:56", run("closed_move.atto"));
    }

    @Test
    void closingRendezvousWakesPendingReaderWithTerminalGuestError() throws Exception {
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run("pending_reader_close.atto"));
        assertTrue(failure.isGuestException(),
                "close must complete the pending reader with a guest-level terminal error");
    }

    @Test
    void drainedClosedChannelTryReceiveIsNotTemporaryEmpty() throws Exception {
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run("try_read_closed_drained.atto"));
        assertTrue(failure.isGuestException(),
                "a closed, drained channel must not return a temporary-empty Option");
    }

    @Test
    void movedPayloadCannotBeReusedAfterAttoStdlibTrySend() throws Exception {
        String guest = Files.readString(
                FIXTURES.resolve("use_after_taken_try_send.atto"), StandardCharsets.UTF_8);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(guest));
        assertTrue(rejected.getMessage().contains("use of moved value"),
                () -> "expected core borrow/move rejection, got: " + rejected.getMessage());
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
        // std/channel is a compiler-owned unit: the linked program runner
        // binds it (a bare Context.eval has no library loader by design).
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        dev.oreslang.runtime.LinkedProgramRunner.run(FIXTURES.resolve(file).toAbsolutePath(),
                dev.oreslang.runtime.IsolatePolicy.developer(),
                dev.oreslang.runtime.ExecutionProfile.serverJit(),
                java.util.Set.of(), java.util.Map.of(), output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}
