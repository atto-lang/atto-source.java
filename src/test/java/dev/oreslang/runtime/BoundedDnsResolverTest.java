package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class BoundedDnsResolverTest {
    @Test
    void timeoutRevokesLateResultWithoutForgingPhysicalRetirement()
            throws Exception {
        AsyncNativeIo nativeIo = new AsyncNativeIo(4, 1, 4);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        OutboundEgressPolicy.Resolver delegate = host -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return List.of(InetAddress.getByName("127.0.0.1"));
        };

        BoundedDnsResolver resolver = new BoundedDnsResolver(
                nativeIo,
                delegate,
                Duration.ofMillis(60),
                1,
                1);
        AtomicReference<Throwable> callerFailure =
                new AtomicReference<>();

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                resolver.resolve("origin.example");
            } catch (Throwable failure) {
                callerFailure.set(failure);
            }
        });

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        caller.join();
        assertInstanceOf(
                UnknownHostException.class,
                callerFailure.get());
        assertEquals(
                1,
                resolver.activeResolutions(),
                "timed-out authorization must not forge resolver-worker retirement");

        resolver.close();
        assertEquals(
                1,
                resolver.activeResolutions(),
                "close requests interruption but the physical receipt remains live");

        release.countDown();
        awaitActive(resolver, 0);
        assertEquals(1, resolver.retiredResolutions());

        nativeIo.close();
    }

    @Test
    void timedOutQueuedLookupRetiresBeforeRunningLookupPhysicallyExits()
            throws Exception {
        AsyncNativeIo nativeIo = new AsyncNativeIo(8, 1, 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        OutboundEgressPolicy.Resolver delegate = host -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return List.of(InetAddress.getByName("127.0.0.1"));
        };
        BoundedDnsResolver resolver = new BoundedDnsResolver(
                nativeIo,
                delegate,
                Duration.ofMillis(150),
                1,
                1);

        AtomicReference<Throwable> firstFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> secondFailure =
                new AtomicReference<>();
        Thread first = resolveAsync(
                resolver,
                "running.example",
                firstFailure);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread second = resolveAsync(
                resolver,
                "queued.example",
                secondFailure);
        awaitActive(resolver, 2);

        second.join();
        first.join();

        assertInstanceOf(
                UnknownHostException.class,
                firstFailure.get());
        assertInstanceOf(
                UnknownHostException.class,
                secondFailure.get());
        assertEquals(
                1,
                resolver.activeResolutions(),
                "queued timeout must retire immediately while the running resolver remains physical");
        assertEquals(
                1,
                resolver.retiredResolutions(),
                "only the queued resolver should have retired before release");

        release.countDown();
        awaitActive(resolver, 0);
        assertEquals(2, resolver.retiredResolutions());

        resolver.close();
        nativeIo.close();
    }

    @Test
    void resolverQueueIsBoundedAndRejectedAdmissionRetiresItsLoan()
            throws Exception {
        AsyncNativeIo nativeIo = new AsyncNativeIo(8, 1, 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        OutboundEgressPolicy.Resolver delegate = host -> {
            entered.countDown();
            awaitIgnoringInterrupts(release);
            return List.of(InetAddress.getByName("127.0.0.1"));
        };
        BoundedDnsResolver resolver = new BoundedDnsResolver(
                nativeIo,
                delegate,
                Duration.ofSeconds(5),
                1,
                1);

        AtomicReference<Throwable> firstFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> secondFailure =
                new AtomicReference<>();
        Thread first = resolveAsync(
                resolver,
                "one.example",
                firstFailure);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread second = resolveAsync(
                resolver,
                "two.example",
                secondFailure);
        awaitActive(resolver, 2);

        UnknownHostException rejected = assertThrows(
                UnknownHostException.class,
                () -> resolver.resolve("three.example"));
        assertInstanceOf(
                RejectedExecutionException.class,
                rejected.getCause());
        assertEquals(
                2,
                resolver.activeResolutions(),
                "rejected queue admission must immediately retire its physical loan");

        resolver.close();
        second.join();
        assertInstanceOf(
                UnknownHostException.class,
                secondFailure.get());

        release.countDown();
        first.join();
        awaitActive(resolver, 0);
        assertNotNull(firstFailure.get());
        nativeIo.close();
    }

    private static Thread resolveAsync(
            BoundedDnsResolver resolver,
            String host,
            AtomicReference<Throwable> failure) {
        return Thread.ofVirtual().start(() -> {
            try {
                resolver.resolve(host);
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
    }

    private static void awaitActive(
            BoundedDnsResolver resolver,
            int expected)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(2);
        while (resolver.activeResolutions() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(
                expected,
                resolver.activeResolutions());
    }

    private static void awaitIgnoringInterrupts(
            CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
