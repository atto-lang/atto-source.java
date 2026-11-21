package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The actual structured ActorCodeExecutor initialization path must bind
 * the SAME actor-owned named i64 storage used from supervisor CONTROL.
 */
final class ActorManagedPrimitiveRegionCodeWorkerTest {
    private record CodeState(
            ActorRuntime.ActorId ownerActorId,
            ActorStateSupervisorLoans region,
            ActorStateSupervisorLoans.LongField balance)
            implements ActorRuntime.ActorOwnedGuestState { }

    @Test
    void structuredCodeWorkerSharesOnlyRegionBackedI64UnderPhysicalLoans()
            throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ExecutorService controlPool = Executors.newFixedThreadPool(2)) {
            var image = images.publish("app/worker.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            ActorRuntime.ControlDispatcher control = work -> {
                var receipt = new OresFuture<Void>();
                controlPool.execute(() -> {
                    try {
                        work.run();
                        receipt.completeFromRuntime(null);
                    } catch (Throwable failure) {
                        receipt.failFromRuntime(failure);
                    }
                });
                return receipt;
            };
            try (var runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    ActorRuntime.DispatcherConfig.defaults(),
                    ActorRuntime.TurnExecutor.direct(), control)) {
                var supervisor = runtime.createSupervisor();
                var group = runtime.createActorGroup(supervisor);
                var stateRef = new AtomicReference<CodeState>();
                var observed = new AtomicLong(-1);
                var grants = new ConcurrentLinkedQueue<ActorStateSupervisorLoans.Grant>();
                ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() {
                        return image;
                    }

                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> ctx) {
                        assertEquals("Worker", type);
                        assertSame(image, ctx.codeImage().orElseThrow());
                        var region = ctx.createManagedPrimitiveStateRegion(8, 2)
                                .orElseThrow(() -> new AssertionError(
                                        "structured code worker needs managed region"));
                        assertTrue(ctx.createManagedPrimitiveStateRegion(8, 2).isEmpty(),
                                "one initialization cannot mint a second mutable field layout");
                        var field = region.declareOwnerLongField("balance", 5L);
                        var state = new CodeState(ctx.self().id(), region, field);
                        stateRef.set(state);
                        return state;
                    }

                    @Override public void receiveActor(
                            String type, ActorRuntime.ActorOwnedGuestState raw,
                            ActorRuntime.ActorInboxMail<Object> mail,
                            ActorRuntime.ActorContext<Object> ctx) {
                        var state = assertInstanceOf(CodeState.class, raw);
                        assertEquals(ctx.self().id(), state.ownerActorId());
                        assertTrue(ctx.createManagedPrimitiveStateRegion(8, 2).isEmpty(),
                                "mailbox turns must not mint a new shared field layout");
                        switch (mail.value().toString()) {
                            case "read" -> observed.set(
                                    state.region().readOwnerLongField(state.balance()));
                            case "inc" -> state.region().updateOwnerLongField(
                                    state.balance(), old -> old + 1);
                            case "delegateRead" -> grants.add(
                                    state.region().delegateRead(supervisor));
                            case "delegateWrite" -> grants.add(
                                    state.region().delegateWrite(supervisor));
                            default -> throw new IllegalArgumentException(mail.value().toString());
                        }
                    }
                };
                try {
                    var actor = group.spawnCodeWorker(
                            ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(), executor, "Worker");
                    actor.ready().get(5, TimeUnit.SECONDS);
                    var state = stateRef.get();
                    assertNotNull(state);
                    actor.send("read");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    assertEquals(5L, observed.get());

                    actor.send("delegateWrite");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    var writer = grants.poll();
                    assertNotNull(writer);
                    runtime.dispatchSupervisorStateControl(supervisor,
                            () -> state.region().withSupervisorWrite(
                                    writer, supervisor, view -> {
                                        assertEquals(5L, view.read(state.balance()));
                                        view.write(state.balance(), 40L);
                                    })).get(5, TimeUnit.SECONDS);
                    actor.send("inc");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    actor.send("read");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    assertEquals(41L, observed.get());

                    actor.send("delegateRead");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    var read = grants.poll();
                    assertNotNull(read);
                    runtime.dispatchSupervisorStateControl(supervisor,
                            () -> state.region().withSupervisorRead(
                                    read, supervisor, view ->
                                            assertEquals(41L, view.read(state.balance()))))
                            .get(5, TimeUnit.SECONDS);
                    assertEquals(0, state.region().activeReaders());
                    assertEquals(0, state.region().activeWriters());

                    actor.send("delegateRead");
                    actor.drain().get(5, TimeUnit.SECONDS);
                    var stale = grants.poll();
                    assertNotNull(stale);
                    assertTrue(actor.cancel());
                    assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                    assertTrue(state.region().physicallyRetired());
                    assertThrows(Exception.class, () ->
                            runtime.dispatchSupervisorStateControl(supervisor,
                                    () -> state.region().withSupervisorRead(
                                            stale, supervisor,
                                            view -> fail("STOP must reject stale grant")))
                                    .get(5, TimeUnit.SECONDS));
                } finally {
                    group.close();
                }
            }
        }
    }

    @Test
    void structuredUntrustedCodeWorkerStillCannotMintSharedPrimitiveRegion()
            throws Exception {
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = images.publish("app/untrusted.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var supervisor = runtime.createSupervisor();
            var group = runtime.createActorGroup(supervisor);
            var admission = new AtomicReference<Boolean>();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> ctx) {
                    admission.set(ctx.createManagedPrimitiveStateRegion(8, 2).isEmpty());
                    return ctx.self()::id;
                }
                @Override public void receiveActor(
                        String type, ActorRuntime.ActorOwnedGuestState raw,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    ctx.self().stop();
                }
            };
            try {
                var worker = group.spawnCodeWorker(
                        ActorRuntime.ActorKind.UNTRUSTED,
                        IsolatePolicy.developer(), executor, "Untrusted");
                worker.ready().get(5, TimeUnit.SECONDS);
                assertEquals(Boolean.TRUE, admission.get(),
                        "an untrusted actor must not gain shared state even with a supervisor");
                worker.send("end");
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                group.close();
            }
        }
    }

    @Test
    void freeCodeActorCannotMintManagedSharedPrimitiveStorage()
            throws Exception {
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = images.publish("app/free.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var visited = new AtomicReference<Boolean>();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String name, ActorRuntime.ActorContext<Object> ctx) {
                    visited.set(ctx.createManagedPrimitiveStateRegion(8, 2).isEmpty());
                    return ctx.self()::id;
                }
                @Override public void receiveActor(
                        String name, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    ctx.self().stop();
                }
            };
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.PRIVATE, executor, "Free");
            actor.ready().get(5, TimeUnit.SECONDS);
            assertEquals(Boolean.TRUE, visited.get(),
                    "unmanaged source actors must not obtain supervised shared-state loan handles");
            actor.send("end");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
