package dev.oreslang.runtime;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same generation registry used by NativeHttp.Handler. */
@Timeout(15)
final class HttpPooledWorkerGenerationRegistryTest {
    @Test
    void keepsReplacedGenerationsUntilEachPhysicalRetirement() {
        var generations = new HttpPooledWorkerGenerationRegistry<String>();
        assertTrue(generations.register("first"));
        assertTrue(generations.register("replacement"));
        assertTrue(generations.register("second-replacement"));
        assertEquals(3, generations.outstanding());
        assertEquals(Set.of("first", "replacement", "second-replacement"),
                Set.copyOf(generations.stopAndSnapshot()),
                "closing a pool cannot inspect only the current worker slot");
        assertTrue(generations.stopping());
        assertFalse(generations.register("post-stop"));
        assertEquals(3, generations.outstanding());
        generations.retire("replacement");
        assertEquals(Set.of("first", "second-replacement"),
                Set.copyOf(generations.stopAndSnapshot()),
                "idempotent close still accounts for older live incarnations");
        generations.retire("first");
        generations.retire("second-replacement");
        assertEquals(0, generations.outstanding());
    }

    @Test
    void refusesDuplicateGenerationWithoutLosingRegistration() {
        var generations = new HttpPooledWorkerGenerationRegistry<Object>();
        Object identity = new Object();
        assertTrue(generations.register(identity));
        assertThrows(IllegalStateException.class, () -> generations.register(identity));
        assertEquals(List.of(identity), generations.stopAndSnapshot());
        assertThrows(NullPointerException.class, () -> generations.register(null));
        assertThrows(NullPointerException.class, () -> generations.retire(null));
    }

    @RepeatedTest(32)
    void concurrentReplacementsCannotEscapeStopSnapshot() throws Exception {
        var generations = new HttpPooledWorkerGenerationRegistry<Object>();
        var accepted = ConcurrentHashMap.<Object>newKeySet();
        for (int i = 0; i < 16; i++) {
            Object initial = new Object();
            assertTrue(generations.register(initial));
            accepted.add(initial);
        }
        int contenders = 96;
        var ready = new CountDownLatch(contenders);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < contenders; i++) {
                tasks.add(threads.submit(() -> {
                    Object candidate = new Object();
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("replacement admission gate timed out");
                    }
                    if (generations.register(candidate)) accepted.add(candidate);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            // register/stop are serialized by the same registry monitor.
            List<Object> snapshot = generations.stopAndSnapshot();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(accepted, Set.copyOf(snapshot),
                    "every incarnation admitted before close must be canceled");
            assertEquals(accepted.size(), generations.outstanding());
            assertFalse(generations.register(new Object()));
            for (Object worker : snapshot) generations.retire(worker);
            assertEquals(0, generations.outstanding());
        }
    }
}
