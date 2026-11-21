package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises exact runtime ownership when registry removal fails before mutation. */
@Timeout(40)
final class ActorRuntimePreReturnAdmissionQuarantineTest {
    @Test
    void sourceActorIsStillPinnedWhenRegistrationAndRemovalBothThrow()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    define actor Counter as
                      let int count;
                      on_start(): void { self.count = 0; return; }
                      receive(ActorMail<int> mail): void { self.send(mail.value); return; }
                    end
                    pub routine main(): void { return; }
                    """;
            var generation = manager.stageActorGeneration("pre-return-quarantine.ores", source);
            var view = generation.linkActorGeneration("Counter");
            ActorRuntime runtime = view.runtime();
            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Object> original =
                    (Map<Object, Object>) actorsField.get(runtime);
            assertTrue(original.isEmpty());
            AtomicReference<ActorRuntime.ActorRef<Object>> admitted =
                    new AtomicReference<>();
            AtomicBoolean failPut = new AtomicBoolean(true);
            AtomicBoolean failRemove = new AtomicBoolean(true);
            var injected = new ConcurrentHashMap<Object, Object>() {
                @Override public Object put(Object key, Object value) {
                    Object previous = super.put(key, value);
                    if (failPut.getAndSet(false))
                        throw new OutOfMemoryError("injected after register");
                    return previous;
                }
                @Override public boolean remove(Object key, Object value) {
                    if (failRemove.getAndSet(false))
                        throw new OutOfMemoryError("injected before unregister");
                    return super.remove(key, value);
                }
            };
            actorsField.set(runtime, injected);
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> runtime.spawnCodeActor(
                                ActorRuntime.ActorKind.SHARED, view.policy(),
                                view.executor(), "Counter", admitted::set));
                assertNotNull(admitted.get(),
                        "source supervisor must receive exact ref before registry put");
                assertEquals(1, injected.size(),
                        "failed cleanup before mutation must retain ghost registration");
                Field countField = ActorRuntime.class.getDeclaredField("actorCount");
                countField.setAccessible(true);
                AtomicInteger count = (AtomicInteger) countField.get(runtime);
                assertEquals(1, count.get(),
                        "registered physical incarnation still owns a runtime slot");
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(admitted.get()),
                        "a surviving registered actor cannot certify code retirement");
                // Every concurrent closer owns the same exact ActorRef.
                // Registry removal, slot refund and final marker publication
                // must be one-shot, even though the ref escaped only through
                // the pre-registration callback and no spawn returned it.
                var closers = new java.util.concurrent.CountDownLatch(1);
                var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread[] threads = new Thread[8];
                for (int i = 0; i < threads.length; i++) {
                    threads[i] = Thread.ofPlatform().start(() -> {
                        try {
                            closers.await();
                            admitted.get().stop();
                        } catch (Throwable problem) {
                            failures.compareAndSet(null, problem);
                        }
                    });
                }
                closers.countDown();
                for (Thread thread : threads) {
                    thread.join(10_000);
                    assertFalse(thread.isAlive(), "physical close race must terminate");
                }
                assertNull(failures.get(),
                        "exact registered identity must have one terminal owner");
                assertTrue(admitted.get().awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(runtime.physicallyFinalizedForHotUpgrade(admitted.get()));
                assertTrue(injected.isEmpty());
                assertEquals(0, count.get(),
                        "all concurrent closers must refund exactly one actor slot");
            } finally {
                actorsField.set(runtime, original);
            }
        }
    }
}
