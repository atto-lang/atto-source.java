package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task started by actor code belongs to that actor's mailbox scheduler.
 * Neither an external Future producer nor the supervisor CONTROL domain can
 * run the task's initial, resumed or nested guest turns.
 */
@Timeout(30)
final class ActorSourceTaskPoolIsolationTest {
    @Test
    void actorTasksAndNestedTasksStayOnOwningPoolAcrossForeignAwait() throws Exception {
        for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
            OresFuture<Integer> producer = new OresFuture<>();
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicReference<OresFuture<Integer>> parent = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> child = new AtomicReference<>();
            AtomicReference<OresScheduler> owner = new AtomicReference<>();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            AtomicReference<Thread> resumedThread = new AtomicReference<>();
            AtomicReference<Throwable> publisherFailure = new AtomicReference<>();
            AtomicInteger controlCalls = new AtomicInteger();
            AtomicInteger parentTurns = new AtomicInteger();
            AtomicInteger childTurns = new AtomicInteger();
            Thread publisher = null;

            // CONTROL is intentionally unavailable in this test. An actor
            // source task accidentally routed there must fail loudly.
            ActorRuntime.ControlDispatcher denyControl = turn -> {
                controlCalls.incrementAndGet();
                throw new AssertionError("actor source Task reached supervisor CONTROL");
            };
            try (SharedCodeImageStore store = new SharedCodeImageStore();
                 ActorRuntime runtime = new ActorRuntime(
                         IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(2, 2, 1, 128),
                         ActorRuntime.TurnExecutor.direct(),
                         denyControl)) {
                var image = store.publish("actor-task-pool.ores",
                        Parser.parse("pub routine main(): void { return; }"));
                ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() {
                        return image;
                    }

                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        assertEquals("PoolWorker", type);
                        return context.self()::id;
                    }

                    @Override public void receiveActor(
                            String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> context) {
                        assertEquals("PoolWorker", type);
                        assertEquals(context.self().id(), state.ownerActorId());
                        if ("stop".equals(mail.value())) {
                            context.self().stop();
                            return;
                        }
                        assertEquals("start", mail.value());
                        assertActorTurn(runtime, kind, context.self().id());
                        parent.set(runtime.startActorTask(resume -> {
                            assertActorTurn(runtime, kind, context.self().id());
                            OresScheduler current = OresScheduler.requireCurrent();
                            assertEquals("ores-actor-" + context.self().id(), current.name());
                            parentTurns.incrementAndGet();
                            if (resume.initial()) {
                                assertTrue(owner.compareAndSet(null, current));
                                suspended.countDown();
                                return OresScheduler.await(producer);
                            }
                            assertSame(owner.get(), current,
                                    "await must not migrate an actor task to another scheduler");
                            resumedThread.set(Thread.currentThread());
                            child.set(runtime.startActorTask(nested -> {
                                assertTrue(nested.initial());
                                assertActorTurn(runtime, kind, context.self().id());
                                assertSame(owner.get(), OresScheduler.requireCurrent(),
                                        "nested task must inherit actor scheduler");
                                assertNotEquals(0L, OresScheduler.currentDispatchId());
                                childTurns.incrementAndGet();
                                return OresScheduler.done(17);
                            }));
                            return OresScheduler.done(11);
                        }));
                    }
                };

                ActorRuntime.ActorRef<Object> actor =
                        runtime.spawnCodeActor(kind, executor, "PoolWorker");
                try {
                    actor.ready().get(4, TimeUnit.SECONDS);
                    actor.send("start");
                    assertTrue(suspended.await(4, TimeUnit.SECONDS),
                            kind + " actor task did not reach await");
                    assertNotNull(parent.get());
                    assertFalse(parent.get().isDone(),
                            "suspended actor task must not finish eagerly");

                    publisher = new Thread(() -> {
                        try {
                            producerThread.set(Thread.currentThread());
                            assertFalse(ActorRuntime.inActorExecution());
                            assertNull(OresScheduler.current());
                            assertThrows(IllegalStateException.class,
                                    () -> runtime.startActorTask(r -> OresScheduler.done(0)),
                                    "a Future producer cannot mint actor tasks");
                            assertTrue(producer.completeFromRuntime(5));
                            assertFalse(ActorRuntime.inActorExecution(),
                                    "the producer must not execute actor continuations");
                        } catch (Throwable failure) {
                            publisherFailure.compareAndSet(null, failure);
                        }
                    }, "foreign-io-completion-" + kind);
                    publisher.start();
                    publisher.join(4000);
                    assertFalse(publisher.isAlive(), "foreign producer did not exit");
                    assertNull(publisherFailure.get(),
                            () -> "foreign completion thread failed: " + publisherFailure.get());

                    assertEquals(11, parent.get().get(4, TimeUnit.SECONDS));
                    assertNotNull(child.get());
                    assertEquals(17, child.get().get(4, TimeUnit.SECONDS));
                    assertNotSame(producerThread.get(), resumedThread.get(),
                            "Future completion thread must not run the actor continuation");
                    assertEquals(2, parentTurns.get());
                    assertEquals(1, childTurns.get());
                    assertEquals(0, controlCalls.get(),
                            "the supervisor CONTROL pool is not an actor task executor");
                    assertTrue(actor.failure().isEmpty(), kind + ": " + actor.failure());

                    actor.send("stop");
                    actor.done().get(4, TimeUnit.SECONDS);
                    assertTrue(actor.failure().isEmpty(), kind + ": " + actor.failure());
                } finally {
                    producer.completeFromRuntime(5);
                    if (publisher != null) publisher.join(4000);
                    actor.cancel();
                }
            }
        }
    }

    private static void assertActorTurn(
            ActorRuntime runtime,
            ActorRuntime.ActorKind kind,
            ActorRuntime.ActorId id) {
        assertTrue(ActorRuntime.isActorCarrierThread(), "must use an actor carrier");
        assertTrue(ActorRuntime.inActorExecution(), "actor context must be installed");
        assertSame(runtime, ActorRuntime.currentActorRuntime());
        assertEquals(kind, ActorRuntime.currentActorKind());
        assertEquals(id, ActorRuntime.currentActorId().orElseThrow());
        assertTrue(runtime.currentThreadOwnsActorExecutionLease(),
                "actor SourceTask must own a physical single-writer turn lease");
    }
}
