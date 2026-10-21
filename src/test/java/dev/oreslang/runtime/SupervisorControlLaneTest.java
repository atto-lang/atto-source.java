package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class SupervisorControlLaneTest {

    private static IsolatePolicy dev() {
        return IsolatePolicy.developer();
    }

    @Test
    void panicControlRunsOnExactParentSingleFlightLaneNotOrdinaryMailbox()
            throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorRef<String>> parent = new AtomicReference<>();
        CountDownLatch ordinaryEntered = new CountDownLatch(1);
        CountDownLatch releaseOrdinary = new CountDownLatch(1);
        CountDownLatch controlObserved = new CountDownLatch(1);
        AtomicBoolean controlOverlappedOrdinary = new AtomicBoolean();
        AtomicBoolean ordinaryActive = new AtomicBoolean();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnShared(context -> {
                context.installSupervisorControlHandler(
                        new ActorRuntime.SupervisorControlHandler() {
                            @Override
                            public ActorRuntime.SupervisorControlDirective onPanic(
                                    ActorRuntime.SupervisorControlContext control,
                                    ActorRuntime.SupervisorControlEvent event) {
                                assertTrue(ActorRuntime.inActorExecution());
                                assertEquals(context.self().id(), control.supervisor().id());
                                assertEquals(
                                        context.self().generation(),
                                        control.supervisor().generation());
                                assertEquals(
                                        context.self().generation(),
                                        event.parentGeneration());
                                controlOverlappedOrdinary.set(ordinaryActive.get());
                                controlObserved.countDown();
                                return ActorRuntime.SupervisorControlDirective.ACKNOWLEDGE;
                            }
                        },
                        new SupervisorRestartLimiter(new SupervisorRestartPolicy(
                                3,
                                Duration.ofMinutes(1),
                                Duration.ZERO,
                                Duration.ZERO)));
                return (message, turn) -> {
                    if ("block".equals(message)) {
                        ordinaryActive.set(true);
                        ordinaryEntered.countDown();
                        assertTrue(releaseOrdinary.await(5, TimeUnit.SECONDS));
                        ordinaryActive.set(false);
                    } else if ("spawn".equals(message)) {
                        ActorRuntime.ActorRef<String> created = runtime.spawn(
                                ActorRuntime.ActorKind.SHARED,
                                dev(),
                                childContext -> (childMessage, ignored) -> {
                                    throw new ActorFailure.Panic("boom");
                                });
                        child.set(created);
                        created.send("fail");
                    }
                };
            });
            parent.set(supervisor);
            supervisor.getReady().get(3, TimeUnit.SECONDS);
            supervisor.send("spawn");
            supervisor.drain().get(3, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> failed = child.get();
            assertNotNull(failed);
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));

            supervisor.send("block");
            assertTrue(ordinaryEntered.await(3, TimeUnit.SECONDS));
            Thread.sleep(25);
            assertEquals(1L, controlObserved.getCount(),
                    "CONTROL must not re-enter while ordinary actor code owns the lane");
            releaseOrdinary.countDown();

            assertTrue(controlObserved.await(5, TimeUnit.SECONDS));
            assertFalse(controlOverlappedOrdinary.get());
            assertEquals(0, runtime.supervisorControlBacklog());

            supervisor.stop();
            supervisor.getDone().get(3, TimeUnit.SECONDS);
        } finally {
            releaseOrdinary.countDown();
        }
    }

    @Test
    void zeroBackoffRestartDirectiveReincarnatesSameChildId() throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
        AtomicInteger incarnations = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnShared(context -> {
                context.installSupervisorControlHandler(
                        new ActorRuntime.SupervisorControlHandler() {
                            @Override
                            public ActorRuntime.SupervisorControlDirective onPanic(
                                    ActorRuntime.SupervisorControlContext control,
                                    ActorRuntime.SupervisorControlEvent event) {
                                assertEquals(
                                        SupervisorRestartLimiter.Action.RESTART,
                                        control.policyDecision().orElseThrow().action());
                                return ActorRuntime.SupervisorControlDirective.RESTART;
                            }
                        },
                        new SupervisorRestartLimiter(new SupervisorRestartPolicy(
                                2,
                                Duration.ofMinutes(1),
                                Duration.ZERO,
                                Duration.ZERO)));
                return (message, turn) -> {
                    if (!"spawn".equals(message)) return;
                    ActorRuntime.ActorRef<String> created = runtime.spawn(
                            ActorRuntime.ActorKind.SHARED,
                            dev(),
                            childContext -> {
                                int incarnation = incarnations.incrementAndGet();
                                return (childMessage, ignored) -> {
                                    if (incarnation == 1) {
                                        throw new ActorFailure.Panic("restart-me");
                                    }
                                };
                            });
                    child.set(created);
                    created.send("fail");
                };
            });
            supervisor.getReady().get(3, TimeUnit.SECONDS);
            supervisor.send("spawn");
            supervisor.drain().get(3, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> failed = child.get();
            assertNotNull(failed);
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));

            ActorRuntime.ActorRef<?> replacement = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (replacement == null && System.nanoTime() < deadline) {
                try {
                    replacement = runtime.controlHandle(failed.id()).ref();
                } catch (IllegalArgumentException ignored) {
                    Thread.sleep(1);
                }
            }
            assertNotNull(replacement);
            assertEquals(failed.id(), replacement.id());
            assertTrue(replacement.generation() > failed.generation());
            assertThrows(
                    ActorRuntime.ActorInvalidReferenceException.class,
                    () -> failed.send("stale"));
            assertEquals(0, runtime.supervisorControlBacklog());

            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
            supervisor.stop();
            supervisor.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void throwingSupervisorHandlerFailsParentButRetainsChildControl()
            throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnShared(context -> {
                context.installSupervisorControlHandler(
                        new ActorRuntime.SupervisorControlHandler() {
                            @Override
                            public ActorRuntime.SupervisorControlDirective onPanic(
                                    ActorRuntime.SupervisorControlContext control,
                                    ActorRuntime.SupervisorControlEvent event) {
                                throw new ActorFailure.Panic("supervisor-handler-panic");
                            }
                        },
                        null);
                return (message, turn) -> {
                    if (!"spawn".equals(message)) return;
                    ActorRuntime.ActorRef<String> created = runtime.spawn(
                            ActorRuntime.ActorKind.SHARED,
                            dev(),
                            childContext -> (childMessage, ignored) -> {
                                throw new ActorFailure.Panic("child-panic");
                            });
                    child.set(created);
                    created.send("fail");
                };
            });
            supervisor.getReady().get(3, TimeUnit.SECONDS);
            supervisor.send("spawn");

            ActorRuntime.ActorRef<String> failed = null;
            long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (failed == null && System.nanoTime() < childDeadline) {
                failed = child.get();
                if (failed == null) Thread.sleep(1);
            }
            assertNotNull(failed);
            final ActorRuntime.ActorRef<String> failedChild = failed;
            assertThrows(
                    ExecutionException.class,
                    () -> failedChild.getDone().get(5, TimeUnit.SECONDS));
            assertThrows(
                    ExecutionException.class,
                    () -> supervisor.getDone().get(5, TimeUnit.SECONDS));

            assertTrue(runtime.peekSupervisorControlEvent(
                            failed.id(), failed.generation())
                    .isPresent(),
                    "child CONTROL must remain retained when handler fails");
            assertTrue(runtime.peekSupervisorControlEvent(
                            supervisor.id(), supervisor.generation())
                    .isPresent(),
                    "failed supervisor must publish its own terminal CONTROL");

            assertTrue(runtime.acknowledgeSupervisorControlEvent(
                    failed.id(), failed.generation()));
            assertTrue(runtime.acknowledgeSupervisorControlEvent(
                    supervisor.id(), supervisor.generation()));
        }
    }

    @Test
    void retainedControlCannotRetargetAfterParentGenerationDies()
            throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> child = new AtomicReference<>();
        CountDownLatch retained = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnShared(context -> {
                context.installSupervisorControlHandler(
                        new ActorRuntime.SupervisorControlHandler() {
                            @Override
                            public ActorRuntime.SupervisorControlDirective onPanic(
                                    ActorRuntime.SupervisorControlContext control,
                                    ActorRuntime.SupervisorControlEvent event) {
                                retained.countDown();
                                return ActorRuntime.SupervisorControlDirective.RETAIN;
                            }
                        },
                        null);
                return (message, turn) -> {
                    if (!"spawn".equals(message)) return;
                    ActorRuntime.ActorRef<String> created = runtime.spawn(
                            ActorRuntime.ActorKind.SHARED,
                            dev(),
                            childContext -> (childMessage, ignored) -> {
                                throw new ActorFailure.Panic("retain");
                            });
                    child.set(created);
                    created.send("fail");
                };
            });
            supervisor.getReady().get(3, TimeUnit.SECONDS);
            supervisor.send("spawn");
            assertTrue(retained.await(5, TimeUnit.SECONDS));

            ActorRuntime.ActorRef<String> failed = child.get();
            assertNotNull(failed);
            supervisor.stop();
            supervisor.getDone().get(5, TimeUnit.SECONDS);

            assertFalse(runtime.redispatchSupervisorControlEvent(
                    failed.id(), failed.generation()),
                    "retained child CONTROL must not retarget another parent generation");
            assertTrue(runtime.peekSupervisorControlEvent(
                    failed.id(), failed.generation()).isPresent());
            assertTrue(runtime.acknowledgeSupervisorControlEvent(
                    failed.id(), failed.generation()));
        }
    }
}
