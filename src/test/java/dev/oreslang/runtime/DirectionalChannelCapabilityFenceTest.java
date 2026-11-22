package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Channel capability regression at the Java runtime/public descriptor boundary.
 * A directional channel or select arm may never return the full bidirectional
 * Channel<T> or acquire close authority, even when its pending operation fails.
 */
final class DirectionalChannelCapabilityFenceTest {
    @Test
    void directionalViewsAndPublicSelectCasesNeverExposeFullChannel() {
        for (Class<?> view : new Class<?>[] {
                ChannelRuntime.ReadChannel.class,
                ChannelRuntime.WriteChannel.class
        }) {
            for (Method method : view.getMethods()) {
                if (method.getDeclaringClass() == Object.class) continue;
                assertNotEquals(ChannelRuntime.Channel.class, method.getReturnType(),
                        view.getSimpleName() + "." + method.getName()
                                + " leaks full channel authority");
                assertNotEquals("close", method.getName(),
                        view.getSimpleName() + " cannot close its backing channel");
                assertNotEquals("backingChannel", method.getName(),
                        view.getSimpleName() + " cannot return a backing channel");
            }
        }
        assertEquals(ChannelRuntime.ReadChannel.class,
                ChannelRuntime.ReadCase.class.getRecordComponents()[0].getType());
        assertEquals(ChannelRuntime.WriteChannel.class,
                ChannelRuntime.WriteCase.class.getRecordComponents()[0].getType());
    }

    @Test
    void directionalPendingWriterFailsOnCloseWithoutLosingAlreadyAcceptedData() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1, 1);
        ChannelRuntime.WriteChannel<Integer> writer = channel.input();
        ChannelRuntime.ReadChannel<Integer> reader = channel.output();
        assertTrue(writer.tryWrite(11));
        OresFuture<Void> pending = writer.writeAsync(22);
        assertFalse(pending.isDone());
        assertEquals(1, channel.pendingRegistrations());
        channel.close();
        CompletionException failure = assertThrows(
                CompletionException.class, pending::join);
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                failure.getCause());
        assertEquals(0, channel.pendingRegistrations());
        assertEquals(11, reader.tryRead().orElseThrow());
        CompletionException afterDrain = assertThrows(
                CompletionException.class, reader::tryRead);
        assertInstanceOf(ChannelRuntime.ChannelClosedException.class,
                afterDrain.getCause());
        assertThrows(CompletionException.class, () -> writer.tryWrite(33));
    }
}
