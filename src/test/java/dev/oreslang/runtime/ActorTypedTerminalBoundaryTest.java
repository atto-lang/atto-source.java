package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.oreslang.runtime.ExceptionBoundaryPolicy.Effect.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs actual ActorRuntime carrier/finalization paths. The typed effect is
 * injected at the compiler/ActorCodeExecutor boundary; guest source
 * throw/raise/panic lowering is not implemented by these fixtures.
 */
@Timeout(20)
final class ActorTypedTerminalBoundaryTest {
    private static void assertTerminated(
            ActorRuntime.ActorRef<?> ref,
            ExceptionBoundaryPolicy.Effect origin, long code) throws Exception {
        assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS),
                "actor did not cross finalization barrier");
        assertThrows(ExecutionException.class,
                () -> ref.done().get(5, TimeUnit.SECONDS));
        var notice = ref.terminalFailureNotice().orElseThrow();
        assertEquals(ref.id(), notice.actor());
        assertEquals(ref.kind(), notice.actorKind());
        assertEquals(PANIC, notice.terminal().delivered());
        assertEquals(origin, notice.terminal().origin());
        assertEquals(code, notice.terminal().originCode());
        assertEquals(1, notice.terminal().defaultExitStatus());
        assertTrue(ref.terminalFailureNotice().orElseThrow() == notice,
                "terminal CONTROL metadata must be immutable and published once");
    }

    @Test
    void typedThrowRaiseAndPanicAreNormalizedOnLiveActorFinalization() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var thrown = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.throwing(17);
                    });
            var raised = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.raising(29);
                    });
            var panicked = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.panicking(31);
                    });

            thrown.send("fail");
            raised.send("fail");
            panicked.send("fail");

            assertTerminated(thrown, THROW, 17);
            assertTerminated(raised, RAISE, 29);
            assertTerminated(panicked, PANIC, 31);
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, thrown.failure().orElseThrow());
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, raised.failure().orElseThrow());
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, panicked.failure().orElseThrow());
        }
    }

    @Test
    void compilerCodeActorBridgeRetainsOriginWithoutCrossingMutableGuestState()
            throws Exception {
        Ast.Program parsed = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("app/failure.ores", parsed);
            ActorRuntime.ActorCodeExecutor compilerBridge = new ActorRuntime.ActorCodeExecutor() {
                @Override
                public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }

                @Override
                public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorType, ActorRuntime.ActorContext<Object> context) {
                    ActorRuntime.ActorId owner = context.self().id();
                    return () -> owner;
                }

                @Override
                public void receiveActor(
                        String actorType, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    assertEquals(context.self().id(), state.ownerActorId());
                    throw ActorRuntime.GuestEffectSignal.raising(0xffff_ffffL);
                }
            };
            var actor = runtime.spawnCodeActor(
                    ActorRuntime.ActorKind.PRIVATE, compilerBridge, "Worker");
            actor.send("raise");
            assertTerminated(actor, RAISE, 0xffff_ffffL);
            var record = actor.terminalFailureNotice().orElseThrow();
            assertEquals(Optional.empty(), record.parent());
            assertEquals(ActorRuntime.GuestEffectSignal.class.getName(),
                    record.diagnosticType());
        }
    }

    @Test
    void arbitraryHostExceptionsAreNotFabricatedAsGuestThrow() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("host-side failure");
                    });
            actor.send("fail");
            assertTerminated(actor, PANIC, 0);
            assertEquals(IllegalStateException.class.getName(),
                    actor.terminalFailureNotice().orElseThrow().diagnosticType());
        }
    }

    @Test
    void structuredCancellationRetainsSeparateTerminalCategory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {});
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class,
                    () -> actor.done().get(5, TimeUnit.SECONDS));
            var notice = actor.terminalFailureNotice().orElseThrow();
            assertEquals(CANCEL, notice.terminal().delivered());
            assertEquals(CANCEL, notice.terminal().origin());
            assertFalse(notice.terminal().delivered() == PANIC);
        }
    }

    @Test
    void gracefulStopHasNoFailureNotice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> context.self().stop());
            actor.send("finish");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(actor.done().get(5, TimeUnit.SECONDS));
            assertTrue(actor.terminalFailureNotice().isEmpty());
        }
    }

    @Test
    void cleanupPanicSupersedesActorThrowAndRetainsTypedSecondary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        context.runtime().endCurrentActorWithCleanup(
                                () -> { throw ActorRuntime.GuestEffectSignal.panicking(99); });
                        throw ActorRuntime.GuestEffectSignal.throwing(7);
                    });
            actor.send("fail-and-cleanup");
            assertTerminated(actor, PANIC, 99);
            var secondary = actor.terminalFailureNotice().orElseThrow()
                    .secondary().orElseThrow();
            assertEquals(THROW, secondary.origin());
            assertEquals(7, secondary.originCode());
        }
    }

    @Test
    void weakerCleanupThrowCannotDowngradeInFlightRaise() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        context.runtime().endCurrentActorWithCleanup(
                                () -> { throw ActorRuntime.GuestEffectSignal.throwing(3); });
                        throw ActorRuntime.GuestEffectSignal.raising(71);
                    });
            actor.send("raise-and-cleanup");
            assertTerminated(actor, RAISE, 71);
            var secondary = actor.terminalFailureNotice().orElseThrow()
                    .secondary().orElseThrow();
            assertEquals(THROW, secondary.origin());
            assertEquals(3, secondary.originCode());
        }
    }

    @Test
    void cancellationOutranksGuestRaiseThatFinishesAfterCancel() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        throw ActorRuntime.GuestEffectSignal.raising(300);
                    });
            actor.send("run");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertTrue(actor.cancel());
            } finally {
                release.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            var notice = actor.terminalFailureNotice().orElseThrow();
            assertEquals(CANCEL, notice.terminal().delivered());
            assertEquals(CANCEL, notice.terminal().origin());
            var displaced = notice.secondary().orElseThrow();
            assertEquals(RAISE, displaced.origin());
            assertEquals(300, displaced.originCode());
        }
    }

    @Test
    void fatalCleanupCannotBeDowngradedToGuestPanic() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        context.runtime().endCurrentActorWithCleanup(
                                () -> { throw new LinkageError("cleanup fatal"); });
                        throw ActorRuntime.GuestEffectSignal.panicking(72);
                    });
            actor.send("fatal-cleanup");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            var notice = actor.terminalFailureNotice().orElseThrow();
            assertEquals(HOST_FATAL, notice.terminal().delivered());
            assertEquals(HOST_FATAL, notice.terminal().origin());
            var displaced = notice.secondary().orElseThrow();
            assertEquals(PANIC, displaced.origin());
            assertEquals(72, displaced.originCode());
            assertInstanceOf(LinkageError.class, actor.failure().orElseThrow());
        }
    }

    @Test
    void actorCannotReadRawThrowableFromAnotherActorOrItself() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        throw ActorRuntime.GuestEffectSignal.throwing(91);
                    });
            target.send("fail");
            assertTerminated(target, THROW, 91);

            var observer = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        assertThrows(SecurityException.class, target::failure);
                        assertThrows(SecurityException.class, context.self()::failure);
                        assertThrows(SecurityException.class, target::terminalFailureNotice);
                        context.self().stop();
                    });
            observer.send("inspect");
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(observer.failure().isEmpty(), "actor code must reject raw error access");
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class,
                    target.failure().orElseThrow()); // still available to the trusted host
        }
    }

    @Test
    void actorAndRootTaskReceiveDetachedFutureFailureInsteadOfRawThrowable()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<String>spawnShared(
                    () -> { throw ActorRuntime.GuestEffectSignal.raising(702); });

            // Trusted host still receives the original exception for diagnostics.
            var readyFailure = assertThrows(ExecutionException.class,
                    () -> target.ready().get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, readyFailure.getCause());
            assertTrue(target.awaitTermination(5, TimeUnit.SECONDS));

            var observedReady = new java.util.concurrent.atomic.AtomicReference<OresFuture<Void>>();
            var observedReadyRef = new java.util.concurrent.atomic.AtomicReference<
                    OresFuture<ActorRuntime.ActorRef<String>>>();
            var observedDone = new java.util.concurrent.atomic.AtomicReference<OresFuture<Void>>();
            var observer = runtime.<String>spawnShared(
                    () -> (message, context) -> {
                        observedReady.set(target.ready());
                        observedReadyRef.set(target.readyRef());
                        observedDone.set(target.done());
                        assertSame(observedDone.get(), target.getDone(),
                                "guest failure view must not allocate on repeated reads");
                        assertFalse(observedDone.get().cancel(false),
                                "failure observer cannot cancel another actor");
                        assertThrows(SecurityException.class, target::failure);
                        context.self().stop();
                    });
            observer.send("observe");
            assertTrue(observer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(observer.failure().isEmpty());

            for (OresFuture<?> view : new OresFuture<?>[]{
                    observedReady.get(), observedReadyRef.get(), observedDone.get()}) {
                assertNotNull(view);
                var wrapped = assertThrows(ExecutionException.class,
                        () -> view.get(5, TimeUnit.SECONDS));
                var snapshot = assertInstanceOf(
                        ActorRuntime.ActorFailureSnapshotException.class, wrapped.getCause());
                assertNull(snapshot.getCause(), "no guest Throwable cause may escape");
                assertEquals(PANIC, snapshot.notice().terminal().delivered());
                assertEquals(RAISE, snapshot.notice().terminal().origin());
                assertEquals(702, snapshot.notice().terminal().originCode());
            }

            try (OresScheduler root = new OresScheduler(1)) {
                var taskView = new java.util.concurrent.atomic.AtomicReference<OresFuture<Void>>();
                var task = root.start(resume -> {
                    taskView.set(target.done());
                    assertThrows(SecurityException.class, target::failure);
                    return OresScheduler.done(1);
                });
                assertEquals(1, task.get(5, TimeUnit.SECONDS));
                var wrapped = assertThrows(ExecutionException.class,
                        () -> taskView.get().get(5, TimeUnit.SECONDS));
                assertInstanceOf(ActorRuntime.ActorFailureSnapshotException.class,
                        wrapped.getCause());
            }
            // Host retains raw exception access without crossing a guest boundary.
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class,
                    assertThrows(ExecutionException.class,
                            () -> target.done().get(5, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test
    void forgedSupervisorMetadataAndOversizeDiagnosticsFailClosed() {
        var id = ActorRuntime.ActorId.create();
        var terminal = ExceptionBoundaryPolicy.actorTerminal(PANIC);
        assertThrows(IllegalArgumentException.class,
                () -> new ActorRuntime.ActorFailureNotice(
                        id, Optional.empty(), ActorRuntime.ActorKind.SHARED,
                        terminal, Optional.empty(), "x".repeat(129)));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorRuntime.ActorFailureNotice(
                        id, Optional.empty(), ActorRuntime.ActorKind.SHARED,
                        terminal, Optional.empty(), "bad" + (char) 10 + "name"));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorRuntime.ActorFailureNotice(
                        id, Optional.empty(), ActorRuntime.ActorKind.SHARED,
                        terminal, Optional.of(
                                ExceptionBoundaryPolicy.taskOrRootTerminal(RETURN)),
                        "java.lang.IllegalStateException"));
    }

    @Test
    void synchronousOneShotActorThrowIsNotConvertedToGracefulStop() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var spawned = new java.util.concurrent.atomic.AtomicReference<
                    ActorRuntime.ActorRef<String>>();
            var signal = assertThrows(ActorRuntime.GuestEffectSignal.class,
                    () -> runtime.invoke(
                            ActorRuntime.ActorKind.SHARED, "throw",
                            (String message, ActorRuntime.ActorContext<String> context) -> {
                                spawned.set(context.self());
                                throw ActorRuntime.GuestEffectSignal.throwing(1001);
                            }));
            assertEquals(THROW, signal.effect());
            assertTerminated(spawned.get(), THROW, 1001);
        }
    }

    @Test
    void immediateAsyncOneShotActorPanicReachesTerminalBoundary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var spawned = new java.util.concurrent.atomic.AtomicReference<
                    ActorRuntime.ActorRef<String>>();
            OresFuture<Integer> future = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED, "panic",
                    (String message, ActorRuntime.ActorContext<String> context) -> {
                        spawned.set(context.self());
                        throw ActorRuntime.GuestEffectSignal.panicking(1002);
                    });
            var failed = assertThrows(java.util.concurrent.CompletionException.class,
                    future::join);
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, failed.getCause());
            assertTerminated(spawned.get(), PANIC, 1002);
        }
    }

    @Test
    void asyncOneShotActorRaiseAfterFutureSuspensionMustPanic() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var spawned = new java.util.concurrent.atomic.AtomicReference<
                    ActorRuntime.ActorRef<String>>();
            OresFuture<Object> future = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED, "raise-after-await",
                    (String message, ActorRuntime.ActorContext<String> context) -> {
                        spawned.set(context.self());
                        return (Object) OresFuture.<Integer>failed(
                                ActorRuntime.GuestEffectSignal.raising(1003));
                    });
            var failed = assertThrows(java.util.concurrent.CompletionException.class,
                    future::join);
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, failed.getCause());
            assertTerminated(spawned.get(), RAISE, 1003);
        }
    }

    @Test
    void delayedAsyncRaiseAfterRegistrationPreservesActorPanic() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var spawned = new java.util.concurrent.atomic.AtomicReference<
                    ActorRuntime.ActorRef<String>>();
            var source = new java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>>();
            var producer = new java.util.concurrent.atomic.AtomicReference<
                    OresFuture.Callback<Integer>>();
            OresFuture<Object> outcome = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED, "delayed-raise",
                    (String message, ActorRuntime.ActorContext<String> context) -> {
                        spawned.set(context.self());
                        OresFuture<Integer> suspended = OresFuture.fromCallback(producer::set);
                        source.set(suspended);
                        return (Object) suspended;
                    });

            // Wait until the actor has registered its continuation. A producer
            // callback must never run guest failure handling on the producer.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((source.get() == null
                    || source.get().pendingRuntimeWaiterCount() == 0)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(source.get(), "actor never created the delayed Future");
            assertTrue(source.get().pendingRuntimeWaiterCount() >= 3,
                    "actor ownership and scheduler continuation must be installed before rejection");
            assertNotNull(producer.get());
            assertFalse(outcome.isDone(), "one-shot result must await actor finalization");
            producer.get().reject(ActorRuntime.GuestEffectSignal.raising(1004));

            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> outcome.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.GuestEffectSignal.class, failure.getCause());
            assertTerminated(spawned.get(), RAISE, 1004);
            assertEquals(0, source.get().pendingRuntimeWaiterCount(),
                    "completed producer must not retain the actor continuation");
        }
    }

    @Test
    void compilerEffectSignalRejectsOutOfRangeCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.GuestEffectSignal.throwing(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.GuestEffectSignal.raising(0x1_0000_0000L));
    }
}
