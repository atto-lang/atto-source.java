package dev.oreslang.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class NativeSocketHandleTest {

    @Test
    void generationsAreUniqueEvenWhenFdNumberIsReused() {
        NativeSocketHandle first = new NativeSocketHandle(7);
        NativeSocketHandle second = new NativeSocketHandle(7);

        assertNotEquals(first.generation(), second.generation());
        assertTrue(first.isOpen());
        assertTrue(second.isOpen());
    }

    @Test
    void closeInvalidatesBeforeFdCanBeReusedThroughStaleHandle() throws Exception {
        NativeSocketHandle handle = new NativeSocketHandle(11);
        AtomicLong closedFd = new AtomicLong(-1);

        handle.closeWith(closedFd::set);

        assertEquals(11, closedFd.get());
        assertFalse(handle.isOpen());
        assertThrows(IOException.class, () -> handle.withFd(fd -> fd));
    }

    @Test
    void closeWaitsForInflightIoBeforeReleasingFd() throws Exception {
        NativeSocketHandle handle = new NativeSocketHandle(13);
        CountDownLatch ioEntered = new CountDownLatch(1);
        CountDownLatch letIoFinish = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);

        Thread io = new Thread(() -> {
            try {
                handle.withFd(fd -> {
                    assertEquals(13, fd);
                    ioEntered.countDown();
                    try {
                        assertTrue(letIoFinish.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return 1;
                });
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        Thread closer = new Thread(() -> {
            try {
                assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
                handle.closeWith(fd -> assertEquals(13, fd));
                closeFinished.countDown();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });

        io.start();
        closer.start();

        assertTrue(ioEntered.await(2, TimeUnit.SECONDS));
        assertFalse(closeFinished.await(50, TimeUnit.MILLISECONDS));
        letIoFinish.countDown();

        io.join(2000);
        closer.join(2000);
        assertFalse(handle.isOpen());
        assertEquals(0, closeFinished.getCount());
    }
}
