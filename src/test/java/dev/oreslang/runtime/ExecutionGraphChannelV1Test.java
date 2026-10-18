package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

final class ExecutionGraphChannelV1Test {
    private static ExecutionGraphV1.Plan<Long> constant(long v) {
        var b = new ExecutionGraphV1.Builder();
        return b.build(b.cpuI64(v));
    }

    @Test
    void explicitRendezvousChannelAppliesBackpressureUntilRead() {
        var output = new ChannelRuntime.Channel<Long>(0, 1);
        var queued = new ArrayDeque<Runnable>();
        var operation = ExecutionGraphChannelV1.publishOne(constant(42L), output, queued::add);
        assertFalse(operation.isDone());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, output.pendingRegistrations());
        assertEquals(42L, output.readAsync().join());
        assertNull(operation.join());
        assertEquals(0, output.pendingRegistrations());
        output.close();
    }

    @Test
    void bufferedChannelIsDistinctFromOneShotFuture() {
        var output = new ChannelRuntime.Channel<Long>(1);
        var queued = new ArrayDeque<Runnable>();
        var completion = ExecutionGraphChannelV1.publishOne(constant(73L), output, queued::add);
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertNull(completion.join());
        assertEquals(73L, output.readAsync().join());
        output.close();
    }

    @Test
    void cancellationBeforeCpuDispatchMustNotPublish() {
        var output = new ChannelRuntime.Channel<Long>(0);
        var queued = new ArrayDeque<Runnable>();
        var completion = ExecutionGraphChannelV1.publishOne(constant(1L), output, queued::add);
        assertEquals(1, queued.size());
        assertTrue(completion.cancel(false));
        queued.remove().run();
        assertTrue(completion.isCancelled());
        assertEquals(0, output.pendingRegistrations());
        assertTrue(output.tryRead().isEmpty());
        output.close();
    }

    @Test
    void cancellingPendingBackpressuredWriteRefundsRegistration() {
        var output = new ChannelRuntime.Channel<Long>(0, 1);
        var queued = new ArrayDeque<Runnable>();
        var completion = ExecutionGraphChannelV1.publishOne(constant(3L), output, queued::add);
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, output.pendingRegistrations());
        assertTrue(completion.cancel(false));
        assertEquals(0, output.pendingRegistrations());
        assertTrue(output.tryRead().isEmpty());
        output.close();
    }

    @Test
    void closedStreamPropagatesFailureWithoutHiddenRetries() {
        var output = new ChannelRuntime.Channel<Long>(1);
        output.close();
        var queued = new ArrayDeque<Runnable>();
        var completion = ExecutionGraphChannelV1.publishOne(constant(3L), output, queued::add);
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                assertThrows(CompletionException.class, completion::join).getCause());
    }

    @Test
    void gpuPlansCannotPublishThroughCpuChannelAsHostData() {
        var g = new ExecutionGraphV1.Builder();
        var plan = g.build(g.downloadI64(g.uploadI64(g.cpuI64(1))));
        var output = new ChannelRuntime.Channel<Long>(1);
        var queued = new ArrayDeque<Runnable>();
        var completion = ExecutionGraphChannelV1.publishOne(plan, output, queued::add);
        assertTrue(queued.isEmpty());
        assertInstanceOf(UnsupportedOperationException.class,
                assertThrows(CompletionException.class, completion::join).getCause());
        assertTrue(output.tryRead().isEmpty());
        output.close();
    }
}
