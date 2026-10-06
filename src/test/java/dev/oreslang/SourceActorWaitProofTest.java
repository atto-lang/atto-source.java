package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.runtime.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.*;

@Timeout(20)
public final class SourceActorWaitProofTest {
    public static final class Probe {
        static volatile State state;
        public static ChannelRuntime.Channel<Number> channel() { return state.channel; }
        public static int before() {
            state.runtime = OresContext.get(null).actors();
            state.actor = ActorRuntime.currentActorId().orElseThrow();
            state.carrier = Thread.currentThread();
            state.dispatch = OresScheduler.currentDispatchId();
            state.armed.countDown();
            return 0;
        }
        public static int after() {
            assertEquals(state.actor, ActorRuntime.currentActorId().orElseThrow());
            assertSame(state.carrier, Thread.currentThread());
            assertNotEquals(state.dispatch, OresScheduler.currentDispatchId());
            state.resumed.countDown();
            return 0;
        }
    }
    static final class State {
        final ChannelRuntime.Channel<Number> channel = new ChannelRuntime.Channel<>(0);
        final CountDownLatch armed = new CountDownLatch(1), resumed = new CountDownLatch(1);
        volatile ActorRuntime runtime;
        volatile ActorRuntime.ActorId actor;
        volatile Thread carrier;
        volatile long dispatch;
        volatile Throwable evaluationFailure;
    }

    @TestFactory
    List<DynamicTest> sourceBlockingWaitsReleaseTheOnlySharedActorCarrier() {
        return List.of("readch", "writech", "await", "select").stream().map(operation ->
                DynamicTest.dynamicTest(operation, () -> prove(operation))).toList();
    }

    private void prove(String operation) throws Exception {
        String property = "ores.runtime.actor.shared.parallelism";
        String prior = System.getProperty(property);
        System.setProperty(property, "1");
        Probe.state = new State();
        State state = Probe.state;
        String wait = switch (operation) {
            case "readch" -> "val int answer = readch channel;";
            case "writech" -> "writech channel, 42; val int answer = 42;";
            case "await" -> "val Future<int> pending = nb readch channel; val int answer = await pending;";
            case "select" -> "let int answer = 0; select { case readch channel: val value answer = value; }";
            default -> throw new AssertionError(operation);
        };
        String program = """
                import fnc before from "java:dev.oreslang.SourceActorWaitProofTest$Probe";
                import fnc after from "java:dev.oreslang.SourceActorWaitProofTest$Probe";
                import fnc channel as get_channel from "java:dev.oreslang.SourceActorWaitProofTest$Probe";
                actor fnc worker(): int {
                  val Channel<int> channel = get_channel();
                  before();
                  %s
                  after();
                  return answer;
                }
                pub routine main(): void {
                  stdio.stdout.write(worker());
                  return;
                }
                """.formatted(wait);
        ExecutorService host = Executors.newSingleThreadExecutor();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.JAVA_INTEROP);
        try {
            Future<?> evaluation = host.submit(() -> {
                try (Context context = policy.restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of(Probe.class.getName()))
                        .out(output).build()) {
                    try { return context.eval(OresLanguage.ID, program); }
                    catch (Throwable failure) { state.evaluationFailure = failure; throw failure; }
                }
            });
            assertTrue(state.armed.await(3, TimeUnit.SECONDS), operation + " never reached source actor");
            assertEquals(1, state.runtime.dispatcherConfig().sharedParallelism());
            assertFalse(evaluation.isDone());
            CountDownLatch peerRan = new CountDownLatch(1);
            AtomicReference<Thread> peerCarrier = new AtomicReference<>();
            ActorRuntime.ActorRef<String> peer = state.runtime.spawnShared(() -> (message, actor) -> {
                peerCarrier.set(Thread.currentThread());
                peerRan.countDown();
            });
            peer.send("same-pool progress");
            assertTrue(peerRan.await(3, TimeUnit.SECONDS), operation + " parked the only shared carrier");
            assertSame(state.carrier, peerCarrier.get());
            if (state.evaluationFailure != null) throw new AssertionError("source evaluation failed", state.evaluationFailure);
            if (evaluation.isDone()) evaluation.get(1, TimeUnit.SECONDS);
            assertEquals(1, state.resumed.getCount(), "wait must still be pending while peer runs");
            if (operation.equals("writech")) assertEquals(42, state.channel.readAsync().get(2, TimeUnit.SECONDS).intValue());
            else assertTrue(state.channel.tryWrite(42));
            evaluation.get(3, TimeUnit.SECONDS);
            assertEquals(0, state.resumed.getCount());
            assertEquals("42", output.toString(StandardCharsets.UTF_8));
            peer.stop();
            assertTrue(peer.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(peer.failure().isEmpty());
        } finally {
            state.channel.close();
            host.shutdownNow();
            host.awaitTermination(2, TimeUnit.SECONDS);
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
            Probe.state = null;
        }
    }
}
