package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.MemorySlotSingletonRegistry;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.ProcessGlobalRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ProcessIdentityIsolationTest {
    // Test-only host probes keep actor factory closures stateless.
    private static volatile CountDownLatch symbolReceived;
    private static final AtomicReference<Object> symbolObserved = new AtomicReference<>();
    private static volatile CountDownLatch sharedCompleted;
    private static final AtomicReference<UUID> sharedInstanceId = new AtomicReference<>();
    private static volatile CountDownLatch privateCompleted;
    private static final AtomicReference<UUID> privateInstanceId = new AtomicReference<>();
    private static volatile CountDownLatch secondCompleted;
    private static final AtomicReference<UUID> secondInstanceId = new AtomicReference<>();
    private static volatile CountDownLatch sandboxCompleted;
    private static final AtomicReference<Throwable> sandboxError = new AtomicReference<>();

    @Test
    void untrustedMailboxAcceptsOnlyExplicitlyExportedSymbols() throws Exception {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        String suffix = UUID.randomUUID().toString();
        OresSymbol hidden = OresSymbol.process("hidden-" + suffix, trusted);
        OresSymbol visible = OresSymbol.process("visible-" + suffix, trusted);

        symbolReceived = new CountDownLatch(1);
        symbolObserved.set(null);

        try (ActorRuntime runtime = new ActorRuntime(trusted)) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivate(
                    untrusted,
                    factoryContext -> (message, turn) -> {
                        symbolObserved.set(message);
                        symbolReceived.countDown();
                        turn.self().stop();
                    });

            assertThrows(SecurityException.class, () -> target.send(hidden));

            OresSymbol.exportToUntrusted(visible, trusted);
            try {
                target.send(List.of("status", visible));
                assertTrue(symbolReceived.await(2, TimeUnit.SECONDS));
                List<?> delivered = assertInstanceOf(List.class, symbolObserved.get());
                assertEquals(visible, delivered.get(1));
            } finally {
                OresSymbol.revokeFromUntrusted(visible, trusted);
            }
        }
    }

    @Test
    void untrustedCodeCanResolveExportedSymbolButCannotInternNewOne() {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        String suffix = UUID.randomUUID().toString();
        OresSymbol exported = OresSymbol.process("error-" + suffix, trusted);
        OresSymbol.exportToUntrusted(exported, trusted);

        try {
            assertSame(exported, OresSymbol.process(exported.key(), untrusted));
            assertThrows(
                    SecurityException.class,
                    () -> OresSymbol.process("attacker-created-" + suffix, untrusted));
            assertThrows(
                    SecurityException.class,
                    () -> OresSymbol.exportToUntrusted(exported, untrusted));
        } finally {
            OresSymbol.revokeFromUntrusted(exported, trusted);
        }
    }

    @Test
    void untrustedMailboxRejectsProcessGlobalReferencesEvenWhenNested() throws Exception {
        IsolatePolicy trusted = IsolatePolicy.developer();
        String key = "global-" + UUID.randomUUID();
        ProcessGlobalRegistry.Handle<ArrayList<Integer>> global =
                ProcessGlobalRegistry.getOrCreate(
                        key,
                        trusted,
                        ArrayList::new);

        try (ActorRuntime runtime = new ActorRuntime(trusted)) {
            ActorRuntime.ActorRef<Object> target = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> { });

            SecurityException direct =
                    assertThrows(SecurityException.class, () -> target.send(global));
            assertTrue(direct.getMessage().contains("global"));

            SecurityException nested = assertThrows(
                    SecurityException.class,
                    () -> target.send(List.of("data", List.of(global))));
            assertTrue(nested.getMessage().contains("global"));
        } finally {
            assertTrue(ProcessGlobalRegistry.collect(key, trusted)
                    .toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void adversarialActorsCanNeverUseSharedActorMemoryDomain() {
        IsolatePolicy adversarialShared = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.SHARED_MEMORY);

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SecurityException denied = assertThrows(
                    SecurityException.class,
                    () -> runtime.<String>spawnShared(
                            adversarialShared,
                            factoryContext -> (message, turn) -> { }));
            assertTrue(denied.getMessage().contains("private isolated"));
        }
    }

    @Test
    void singletonIsUniquePerMemorySlotAndSharedActorsUseMainSlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> main =
                    runtime.singletons().getOrCreate("counter", ArrayList::new);
            UUID mainId = main.instanceId();

            sharedCompleted = new CountDownLatch(1);
            sharedInstanceId.set(null);
            ActorRuntime.ActorRef<String> shared = runtime.spawnShared(
                    factoryContext -> (message, turn) -> {
                        MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> local =
                                turn.runtime().singletons().getOrCreate(
                                        "counter",
                                        ArrayList::new);
                        sharedInstanceId.set(local.instanceId());
                        local.call(List.of(7), (state, args) -> {
                            state.add((Integer) args.getFirst());
                            return state.size();
                        });
                        sharedCompleted.countDown();
                        turn.self().stop();
                    });
            shared.send("go");
            assertTrue(sharedCompleted.await(2, TimeUnit.SECONDS));
            assertEquals(mainId, sharedInstanceId.get());

            assertEquals(
                    1,
                    main.call(List.of(), (state, args) -> state.size()));

            privateInstanceId.set(null);
            privateCompleted = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> firstPrivate = runtime.spawnPrivate(
                    factoryContext -> (message, turn) -> {
                        privateInstanceId.set(
                                turn.runtime().singletons()
                                        .getOrCreate("counter", ArrayList::new)
                                        .instanceId());
                        privateCompleted.countDown();
                        turn.self().stop();
                    });
            firstPrivate.send("go");
            assertTrue(privateCompleted.await(2, TimeUnit.SECONDS));
            assertNotEquals(mainId, privateInstanceId.get());
            assertTrue(firstPrivate.awaitTermination(2, TimeUnit.SECONDS));

            secondInstanceId.set(null);
            secondCompleted = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> secondPrivate = runtime.spawnPrivate(
                    factoryContext -> (message, turn) -> {
                        secondInstanceId.set(
                                turn.runtime().singletons()
                                        .getOrCreate("counter", ArrayList::new)
                                        .instanceId());
                        secondCompleted.countDown();
                        turn.self().stop();
                    });
            secondPrivate.send("go");
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS));
            assertNotEquals(privateInstanceId.get(), secondInstanceId.get());
        }
    }

    @Test
    void untrustedActorCannotCreateMemorySlotSingletonEvenThroughRuntimeApi() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            sandboxCompleted = new CountDownLatch(1);
            sandboxError.set(null);

            ActorRuntime.ActorRef<String> sandbox = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> {
                        try {
                            turn.runtime().singletons().getOrCreate(
                                    "forbidden",
                                    ArrayList::new);
                        } catch (Throwable failure) {
                            sandboxError.set(failure);
                        } finally {
                            sandboxCompleted.countDown();
                            turn.self().stop();
                        }
                    });

            sandbox.send("go");
            assertTrue(sandboxCompleted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, sandboxError.get());
        }
    }

    @Test
    void singletonHandleCannotBeUsedFromAnotherMemorySlot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> main =
                    runtime.singletons().getOrCreate("slot-bound", ArrayList::new);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            ActorRuntime.ActorRef<String> privateActor = runtime.spawnPrivateTrusted(
                    factoryContext -> (message, turn) -> {
                        try {
                            main.call(List.of(), (state, args) -> state.size());
                        } catch (Throwable failure) {
                            observed.set(failure);
                        } finally {
                            done.countDown();
                            turn.self().stop();
                        }
                    });

            privateActor.send("go");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, observed.get());
        }
    }
    @Test
    void untrustedMailboxRejectsActorRefsAsLiveAuthority() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            ActorRuntime.ActorRef<String> trustedTarget = runtime.spawnPrivateTrusted(
                    factoryContext -> (message, turn) -> { });
            ActorRuntime.ActorRef<Object> sandbox = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factoryContext -> (message, turn) -> { });

            SecurityException direct = assertThrows(
                    SecurityException.class,
                    () -> sandbox.send(trustedTarget));
            assertTrue(direct.getMessage().contains("actor"));

            assertThrows(
                    SecurityException.class,
                    () -> sandbox.send(List.of("nested", List.of(trustedTarget))));
        }
    }

    @Test
    void wellKnownSymbolsStillRequireExplicitSandboxExport() {
        IsolatePolicy trusted = IsolatePolicy.developer();
        IsolatePolicy untrusted = IsolatePolicy.strictFaas();
        OresSymbol iterator = OresSymbol.wellKnown("iterator", trusted);

        if (iterator.visibleToUntrusted()) {
            OresSymbol.revokeFromUntrusted(iterator, trusted);
        }
        assertThrows(
                SecurityException.class,
                () -> OresSymbol.wellKnown("iterator", untrusted));

        OresSymbol.exportToUntrusted(iterator, trusted);
        try {
            assertEquals(iterator, OresSymbol.wellKnown("iterator", untrusted));
        } finally {
            OresSymbol.revokeFromUntrusted(iterator, trusted);
        }
    }

    @Test
    void singletonRejectsReentrantCallIntoSameCell() {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> singleton =
                    runtime.singletons().getOrCreate("reentrant", ArrayList::new);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> singleton.call(
                            List.of(),
                            (state, args) -> singleton.call(
                                    List.of(),
                                    (sameState, sameArgs) -> sameState.size())));
            assertTrue(failure.getMessage().contains("reentrant"));
        }
    }

    @Test
    void singletonWaitGraphRejectsCrossCellDeadlockCycle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> left =
                    runtime.singletons().getOrCreate("cycle-left", ArrayList::new);
            MemorySlotSingletonRegistry.Handle<ArrayList<Integer>> right =
                    runtime.singletons().getOrCreate("cycle-right", ArrayList::new);
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Object> a = executor.submit(() -> left.call(
                        List.of(),
                        (state, args) -> {
                            barrier.await(2, TimeUnit.SECONDS);
                            return right.call(
                                    List.of(),
                                    (rightState, rightArgs) -> rightState.size());
                        }));
                Future<Object> b = executor.submit(() -> right.call(
                        List.of(),
                        (state, args) -> {
                            barrier.await(2, TimeUnit.SECONDS);
                            return left.call(
                                    List.of(),
                                    (leftState, leftArgs) -> leftState.size());
                        }));

                Throwable aFailure = futureFailure(a);
                Throwable bFailure = futureFailure(b);
                assertTrue(
                        containsMessage(aFailure, "cycle")
                                || containsMessage(bFailure, "cycle"),
                        "at least one side must reject the lock cycle");
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static Throwable futureFailure(Future<?> future) throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private static boolean containsMessage(Throwable failure, String fragment) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().toLowerCase().contains(fragment.toLowerCase())) {
                return true;
            }
        }
        return false;
    }


}
