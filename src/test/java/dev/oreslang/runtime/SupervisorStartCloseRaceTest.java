package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class SupervisorStartCloseRaceTest {
    @Test
    void closeCompetingWithStartNeverLeavesLiveUnmanagedChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var s = runtime.createSupervisor();
            for (int i = 0; i < 24; i++) {
                var group = runtime.createActorGroup(s);
                var gate = new CountDownLatch(1);
                AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
                AtomicReference<Throwable> error = new AtomicReference<>();
                Thread start = new Thread(() -> {
                    try {
                        gate.await();
                        child.set(s.startChildHost(group, ActorRuntime.ActorKind.PRIVATE,
                                IsolatePolicy.developer(), factory -> (mail, ctx) -> { }));
                    } catch (IllegalStateException closed) {
                        // Expected if closing wins admission.
                    } catch (Throwable unexpected) {
                        error.compareAndSet(null, unexpected);
                    }
                });
                Thread stop = new Thread(() -> {
                    try {
                        gate.await();
                        group.close();
                    } catch (Throwable unexpected) {
                        error.compareAndSet(null, unexpected);
                    }
                });
                start.start();
                stop.start();
                gate.countDown();
                start.join(5000);
                stop.join(5000);
                assertFalse(start.isAlive());
                assertFalse(stop.isAlive());
                assertNull(error.get());
                var ref = child.get();
                if (ref != null) assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(0, group.workerCount());
                assertEquals(0, runtime.actorCount());
            }
        }
    }
}
