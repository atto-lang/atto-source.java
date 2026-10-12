package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Narrow white-box test of the root admission state machine.
 * It injects only a compiler-owned LinearPipeline descriptor because ordinary
 * Java host actors deliberately cannot call bindLinearPipeline(); the real
 * compiled guest and public binder are exercised by the separate differential
 * test. All rejected and accepted messages use the real public send + codec
 * + DATA mailbox path. */
final class ActorPipelineRootAdmissionCreditTest {
    @SuppressWarnings("unchecked")
    @Test
    void rejectedRootTaggedDataDoesNotBurnItsOnlyAdmissionCredit() throws Exception {
        var registry = TaggedMessageContracts.verify(Parser.parse("""
                define enum Signal as
                    Data: int = 1
                    Result: int = 2
                end
                define pattern PipeMessages as
                    Signal.Data
                    Signal.Result
                end
                """));
        var codec = new TaggedMessageCodec(registry.requireDomain("PipeMessages"));

        try (ActorRuntime runtime = new ActorRuntime()) {
            var deliveries = new ArrayBlockingQueue<Object>(4);
            var source = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> deliveries.add(value));
            var transform = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> { });
            var sink = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> { });
            runtime.bindSerializableMailbox(source, codec);

            Class<?> routeClass = Arrays.stream(ActorRuntime.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("LinearPipeline"))
                    .findFirst().orElseThrow();
            Constructor<?> ctor = routeClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            AtomicInteger phase = new AtomicInteger(0);
            Object route = ctor.newInstance(java.util.List.of(source, transform, sink),
                    codec.closedDomainIdentity(), phase);
            Field registryField = ActorRuntime.class.getDeclaredField("linearPipelines");
            registryField.setAccessible(true);
            ConcurrentHashMap<ActorRuntime.ActorId, Object> routes =
                    (ConcurrentHashMap<ActorRuntime.ActorId, Object>) registryField.get(runtime);
            routes.put(source.id(), route);
            routes.put(transform.id(), route);
            routes.put(sink.id(), route);

            var malformed = new TaggedMessageCodec.TaggedValue(
                    "Signal", "Data", 1, "not-an-int64");
            var valid = new TaggedMessageCodec.TaggedValue(
                    "Signal", "Data", 1, 5L);

            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> source.send(malformed));
                assertEquals(0, phase.get(),
                        "rejected root DATA must not consume the only pipeline flow");
                assertNull(deliveries.poll(20, TimeUnit.MILLISECONDS),
                        "rejected DATA must not reach the actor mailbox");
            }
            source.send(valid);
            assertEquals(1, phase.get(), "successful DATA publication commits root ownership");
            assertEquals(valid, deliveries.poll(5, TimeUnit.SECONDS),
                    "recipient must receive the exact validated nominal message");
            assertThrows(SecurityException.class, () -> source.send(valid),
                    "a committed single-flow root must reject duplicates");
            assertEquals(1, phase.get(), "duplicate DATA cannot corrupt committed phase");
            assertNull(deliveries.poll(30, TimeUnit.MILLISECONDS));
        }
    }
}
