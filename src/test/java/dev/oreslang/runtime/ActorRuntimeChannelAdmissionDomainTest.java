package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorRuntimeChannelAdmissionDomainTest {
    @Test
    void actorOutputWaitersAreChargedOnlyToTheirOwningRuntime() throws Exception {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            ActorRuntime.ActorRef<Integer> leftActor =
                    left.spawnShared(() -> (message, context) -> { });
            ActorRuntime.ActorRef<Integer> rightActor =
                    right.spawnShared(() -> (message, context) -> { });

            OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> leftWait =
                    leftActor.outputs().next();
            assertFalse(leftWait.isDone());
            assertEquals(1, left.channelAdmissionDomain().pendingSelections());
            assertEquals(0, right.channelAdmissionDomain().pendingSelections());

            OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> rightWait =
                    rightActor.outputs().next();
            assertFalse(rightWait.isDone());
            assertEquals(1, left.channelAdmissionDomain().pendingSelections());
            assertEquals(1, right.channelAdmissionDomain().pendingSelections());

            assertTrue(leftWait.cancel(false));
            assertEquals(0, left.channelAdmissionDomain().pendingSelections());
            assertEquals(1, right.channelAdmissionDomain().pendingSelections());

            assertTrue(rightWait.cancel(false));
            assertEquals(0, right.channelAdmissionDomain().pendingSelections());

            leftActor.stop();
            rightActor.stop();
            leftActor.getDone().get(5, TimeUnit.SECONDS);
            rightActor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void eventBusAsyncReadsUseOwningActorRuntimeDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic(
                    "updates", ActorEventBus.DeliveryPolicy.RELIABLE, 1);

            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> subscriber =
                    runtime.spawnShared(() -> (message, context) -> {
                        try {
                            join.join();
                            ActorEventBus.Subscription<Integer> subscription =
                                    group.events().subscribe("updates");
                            OresFuture<ActorEventBus.Event<Integer>> pending =
                                    subscription.readAsync();
                            assertFalse(pending.isDone());
                            assertEquals(
                                    1,
                                    context.runtime()
                                            .channelAdmissionDomain()
                                            .pendingSelections(),
                                    "subscription waiter must charge its ActorRuntime domain");
                            assertTrue(pending.cancel(false));
                            assertEquals(
                                    0,
                                    context.runtime()
                                            .channelAdmissionDomain()
                                            .pendingSelections());
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            checked.countDown();
                        }
                    });

            subscriber.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            subscriber.stop();
            subscriber.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorsInOneRuntimeOwnDistinctSourceDomainsAndStopFencesPendingSelects()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(2);
            AtomicReference<ChannelRuntime.AdmissionDomain> leftDomain =
                    new AtomicReference<>();
            AtomicReference<ChannelRuntime.AdmissionDomain> rightDomain =
                    new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> leftPending =
                    new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> rightPending =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> left =
                    runtime.spawnShared(() -> (message, context) -> {
                        ChannelRuntime.AdmissionDomain domain =
                                context.runtime().currentSourceChannelAdmissionDomain();
                        leftDomain.set(domain);
                        ChannelRuntime.Channel<Integer> channel =
                                new ChannelRuntime.Channel<>(0, domain);
                        leftPending.set(channel.readAsync());
                        entered.countDown();
                        context.self().stop();
                    });
            ActorRuntime.ActorRef<Integer> right =
                    runtime.spawnShared(() -> (message, context) -> {
                        ChannelRuntime.AdmissionDomain domain =
                                context.runtime().currentSourceChannelAdmissionDomain();
                        rightDomain.set(domain);
                        ChannelRuntime.Channel<Integer> channel =
                                new ChannelRuntime.Channel<>(0, domain);
                        rightPending.set(channel.readAsync());
                        entered.countDown();
                        context.self().stop();
                    });

            left.send(1);
            right.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            left.getDone().get(5, TimeUnit.SECONDS);
            right.getDone().get(5, TimeUnit.SECONDS);

            assertNotNull(leftDomain.get());
            assertNotNull(rightDomain.get());
            assertNotSame(leftDomain.get(), rightDomain.get(),
                    "actors sharing one ActorRuntime must not share normal source select quota");
            assertTrue(leftDomain.get().isClosed());
            assertTrue(rightDomain.get().isClosed());
            assertEquals(0, leftDomain.get().pendingSelections());
            assertEquals(0, rightDomain.get().pendingSelections());

            assertTrue(leftPending.get().isCompletedExceptionally());
            assertTrue(rightPending.get().isCompletedExceptionally());
        }
    }

    @Test
    void runtimeClosePublishesOutputTerminalBeforeClosingAdmissionDomain() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        ActorRuntime.ActorRef<Integer> actor =
                runtime.spawnShared(() -> (message, context) -> { });
        OresFuture<GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>>> pending =
                actor.outputs().next();

        assertFalse(pending.isDone());
        assertEquals(1, runtime.channelAdmissionDomain().pendingSelections());

        runtime.close();

        GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> terminal =
                pending.get(5, TimeUnit.SECONDS);
        assertTrue(terminal.done(),
                "normal actor output closure must win before the residual domain fence");
        assertTrue(runtime.channelAdmissionDomain().isClosed());
        assertEquals(0, runtime.channelAdmissionDomain().pendingSelections());
        assertEquals(0, runtime.channelAdmissionDomain().pendingArms());
    }
}
