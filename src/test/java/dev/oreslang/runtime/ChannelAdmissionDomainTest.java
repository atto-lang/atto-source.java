package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ChannelAdmissionDomainTest {
    @Test
    void oneDomainCannotConsumeAnotherDomainsPendingSelectBudget() throws Exception {
        var domainA = new ChannelRuntime.AdmissionDomain("domain-a", 1, 8);
        var domainB = new ChannelRuntime.AdmissionDomain("domain-b", 1, 8);
        var channelA = new ChannelRuntime.Channel<Integer>(0, 8, domainA);
        var channelB = new ChannelRuntime.Channel<Integer>(0, 8, domainB);

        OresFuture<Integer> aPending = channelA.readAsync();
        assertFalse(aPending.isDone());
        assertEquals(1, domainA.pendingSelections());

        OresFuture<Integer> aOverflow = channelA.readAsync();
        ExecutionException overflow = assertThrows(
                ExecutionException.class,
                () -> aOverflow.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ChannelRuntime.SelectAdmissionException.class, overflow.getCause());
        assertEquals(1, domainA.pendingSelections(),
                "rejected transient selection must refund domain accounting");

        OresFuture<Integer> bPending = channelB.readAsync();
        assertFalse(bPending.isDone(),
                "domain B must retain its own quota even while A is saturated");
        assertEquals(1, domainB.pendingSelections());

        assertTrue(channelB.tryWrite(42),
                "ready rendezvous in B must commit despite B's retained selection count");
        assertEquals(42, bPending.get(1, TimeUnit.SECONDS));
        assertEquals(0, domainB.pendingSelections());

        assertTrue(aPending.cancel(false));
        assertEquals(0, domainA.pendingSelections());
        domainA.close();
        domainB.close();
    }

    @Test
    void readyOperationCanCommitBeforePendingQuotaRejection() throws Exception {
        var domain = new ChannelRuntime.AdmissionDomain("ready-progress", 1, 8);
        var blocked = new ChannelRuntime.Channel<Integer>(0, 8, domain);
        var buffered = new ChannelRuntime.Channel<Integer>(1, 8, domain);

        assertTrue(buffered.tryWrite(7));
        OresFuture<Integer> pending = blocked.readAsync();
        assertFalse(pending.isDone());
        assertEquals(1, domain.pendingSelections());

        // This transient registration temporarily exceeds the domain count, but
        // it is immediately ready and must commit before retained-waiter quotas
        // are enforced.
        assertEquals(7, buffered.readAsync().get(1, TimeUnit.SECONDS));
        assertEquals(1, domain.pendingSelections());

        assertTrue(pending.cancel(false));
        domain.close();
    }

    @Test
    void crossDomainAtomicSelectFailsClosed() {
        var domainA = new ChannelRuntime.AdmissionDomain("left", 4, 8);
        var domainB = new ChannelRuntime.AdmissionDomain("right", 4, 8);
        var left = new ChannelRuntime.Channel<Integer>(0, 8, domainA);
        var right = new ChannelRuntime.Channel<Integer>(0, 8, domainB);

        assertThrows(
                ChannelRuntime.SelectAdmissionException.class,
                () -> new ChannelRuntime.SelectSet(List.of(
                        ChannelRuntime.read(left),
                        ChannelRuntime.read(right))));

        assertThrows(
                ChannelRuntime.SelectAdmissionException.class,
                () -> new ChannelRuntime.SelectSet(
                        List.of(ChannelRuntime.read(left)),
                        domainB));

        domainA.close();
        domainB.close();
    }

    @Test
    void closingDomainFailsOnlyItsPendingSelectionsAndRefundsAccounting() throws Exception {
        var domainA = new ChannelRuntime.AdmissionDomain("closing-a", 4, 8);
        var domainB = new ChannelRuntime.AdmissionDomain("live-b", 4, 8);
        var channelA = new ChannelRuntime.Channel<Integer>(0, 8, domainA);
        var channelB = new ChannelRuntime.Channel<Integer>(0, 8, domainB);

        OresFuture<Integer> aPending = channelA.readAsync();
        OresFuture<Integer> bPending = channelB.readAsync();
        IllegalStateException cause = new IllegalStateException("context teardown");

        domainA.close(cause);

        ExecutionException closed = assertThrows(
                ExecutionException.class,
                () -> aPending.get(1, TimeUnit.SECONDS));
        assertInstanceOf(
                ChannelRuntime.AdmissionDomainClosedException.class,
                closed.getCause());
        assertSame(cause, closed.getCause().getCause());
        assertEquals(0, domainA.pendingSelections());
        assertEquals(0, domainA.pendingArms());

        assertFalse(bPending.isDone());
        assertTrue(channelB.tryWrite(9));
        assertEquals(9, bPending.get(1, TimeUnit.SECONDS));

        OresFuture<Integer> postClose = channelA.readAsync();
        ExecutionException postCloseFailure = assertThrows(
                ExecutionException.class,
                () -> postClose.get(1, TimeUnit.SECONDS));
        assertInstanceOf(
                ChannelRuntime.AdmissionDomainClosedException.class,
                postCloseFailure.getCause());

        domainB.close();
    }

    @Test
    void domainCloseRacingFutureAttachmentDetachesBeforePublishingFailure() throws Exception {
        var domain = new ChannelRuntime.AdmissionDomain("attach-close", 4, 8);
        var producer = new OresFuture<Integer>();
        var set = new ChannelRuntime.SelectSet(
                List.of(ChannelRuntime.await(producer)),
                domain);

        OresFuture<ChannelRuntime.SelectResult> selected =
                set.selectAsyncForTesting(
                        ChannelRuntime.SelectPolicy.PRIORITY,
                        ignored -> { },
                        () -> domain.close(
                                new IllegalStateException("close during attachment")));

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> selected.get(1, TimeUnit.SECONDS));
        assertInstanceOf(
                ChannelRuntime.AdmissionDomainClosedException.class,
                failure.getCause());
        assertEquals(0, producer.pendingRuntimeWaiterCount(),
                "domain close must not leave a late Future listener attached");
        assertEquals(0, domain.pendingSelections());
        assertEquals(0, domain.pendingArms());
    }

    @Test
    void futureOnlySelectCanBeChargedToExplicitDomain() throws Exception {
        var domain = new ChannelRuntime.AdmissionDomain("future-only", 1, 8);
        OresFuture<Integer> producerA = new OresFuture<>();
        OresFuture<Integer> producerB = new OresFuture<>();

        OresFuture<ChannelRuntime.SelectResult> first =
                new ChannelRuntime.SelectSet(
                        List.of(ChannelRuntime.await(producerA)),
                        domain)
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(first.isDone());
        assertEquals(1, domain.pendingSelections());

        OresFuture<ChannelRuntime.SelectResult> overflow =
                new ChannelRuntime.SelectSet(
                        List.of(ChannelRuntime.await(producerB)),
                        domain)
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> overflow.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ChannelRuntime.SelectAdmissionException.class, failure.getCause());

        producerA.completeFromRuntime(11);
        assertEquals(11, first.get(1, TimeUnit.SECONDS).value());
        assertEquals(0, domain.pendingSelections());
        domain.close();
    }
}
