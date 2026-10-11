package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ProcessSingletonRegistry;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ProcessSingletonContractTest {
    private static final IsolatePolicy TRUSTED = IsolatePolicy.developer();

    @Test
    void concurrentInitializationCreatesExactlyOneFrozenSnapshot() throws Exception {
        String key = "concurrent-singleton-" + UUID.randomUUID();
        AtomicInteger initialized = new AtomicInteger();
        ArrayList<Integer> source = new ArrayList<>(List.of(1, 2, 3));

        try (var workers = Executors.newFixedThreadPool(12)) {
            List<Future<UUID>> requests = new ArrayList<>();
            for (int i = 0; i < 48; i++) {
                requests.add(workers.submit(() -> ProcessSingletonRegistry.getOrCreate(
                        key, "counter-schema-1", TRUSTED, () -> {
                            initialized.incrementAndGet();
                            return Map.of("values", source);
                        }).instanceId()));
            }
            UUID id = requests.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<UUID> request : requests) {
                assertEquals(id, request.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, initialized.get());
        }

        ProcessSingletonRegistry.Handle handle = ProcessSingletonRegistry.getOrCreate(
                key, "counter-schema-1", TRUSTED,
                () -> fail("second lookup must not reinitialize"));
        source.add(4);
        assertEquals(List.of(1, 2, 3), handle.snapshot().fields().get("values"));
        assertThrows(UnsupportedOperationException.class,
                () -> handle.snapshot().fields().put("extra", 42));
        @SuppressWarnings("unchecked")
        List<Integer> frozenValues = (List<Integer>) handle.snapshot().fields().get("values");
        assertThrows(UnsupportedOperationException.class, () -> frozenValues.add(5));
        assertThrows(IllegalStateException.class, () -> ProcessSingletonRegistry.getOrCreate(
                key, "incompatible-schema", TRUSTED, Map::of));
    }

    @Test
    void sharedAndTrustedPrivateActorObserveSameInstance() throws Exception {
        String key = "actor-singleton-" + UUID.randomUUID();
        UUID hostId = ProcessSingletonRegistry.getOrCreate(
                key, "schema-v1", TRUSTED, () -> Map.of("region", "nyc")).instanceId();
        try (ActorRuntime runtime = new ActorRuntime(TRUSTED)) {
            CountDownLatch finished = new CountDownLatch(2);
            AtomicReference<UUID> sharedSeen = new AtomicReference<>();
            AtomicReference<UUID> privateSeen = new AtomicReference<>();

            ActorRuntime.ActorRef<String> shared = runtime.spawnShared(
                    factory -> (message, turn) -> {
                        sharedSeen.set(ProcessSingletonRegistry.getOrCreate(
                                key, "schema-v1", turn.policy(), () -> Map.of()).instanceId());
                        finished.countDown();
                        turn.self().stop();
                    });
            ActorRuntime.ActorRef<String> isolated = runtime.spawnPrivate(
                    factory -> (message, turn) -> {
                        privateSeen.set(ProcessSingletonRegistry.getOrCreate(
                                key, "schema-v1", turn.policy(), () -> Map.of()).instanceId());
                        finished.countDown();
                        turn.self().stop();
                    });
            shared.send("read");
            isolated.send("read");
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(hostId, sharedSeen.get());
            assertEquals(hostId, privateSeen.get());
        }
    }

    @Test
    void untrustedActorCannotCreateOrReadCapturedSingletonHandle() throws Exception {
        String key = "untrusted-singleton-" + UUID.randomUUID();
        ProcessSingletonRegistry.Handle captured = ProcessSingletonRegistry.getOrCreate(
                key, "schema-v1", TRUSTED, () -> Map.of("secret", "hidden"));
        assertThrows(SecurityException.class, () -> ProcessSingletonRegistry.getOrCreate(
                key, "schema-v1", IsolatePolicy.strictFaas(), Map::of));

        try (ActorRuntime runtime = new ActorRuntime(TRUSTED)) {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> denied = new AtomicReference<>();
            ActorRuntime.ActorRef<String> sandbox = runtime.spawnPrivate(
                    IsolatePolicy.strictFaas(),
                    factory -> (message, turn) -> {
                        try {
                            captured.snapshot();
                        } catch (Throwable failure) {
                            denied.set(failure);
                        } finally {
                            finished.countDown();
                            turn.self().stop();
                        }
                    });
            sandbox.send("read");
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, denied.get());
        }
    }

    @Test
    void rejectsLiveCapabilitiesAndRequiresActualCrossIsolateCoordinator() {
        String key = "forbidden-state-" + UUID.randomUUID();
        try (ActorRuntime runtime = new ActorRuntime(TRUSTED)) {
            assertThrows(IllegalArgumentException.class, () -> ProcessSingletonRegistry.getOrCreate(
                    key, "schema-v1", TRUSTED,
                    () -> Map.of("leak", runtime.spawnShared(
                            factory -> (message, turn) -> turn.self().stop()))));
        }
        assertThrows(IllegalStateException.class,
                () -> ProcessSingletonRegistry.requireBackendFor(true));
        assertDoesNotThrow(() -> ProcessSingletonRegistry.requireBackendFor(false));
    }

    @Test
    void compilerRejectsMutableAndFirstCallerDependentSingletonState() {
        IllegalArgumentException mutable = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module Bad
                          let int count = 0;
                        end
                        """)));
        assertTrue(mutable.getMessage().contains("write-once"));

        IllegalArgumentException impure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define singleton module Bad
                          pub val int count = 1 + now();
                        end
                        """)));
        assertTrue(impure.getMessage().contains("constant")
                || impure.getMessage().contains("unknown"));
    }
}
