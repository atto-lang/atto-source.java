package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the ACTUAL actor mailbox -> host/source handler boundary.
 * These are handler-call timestamps, not proof of guest instruction start.
 */
@ResourceLock(value = org.junit.jupiter.api.parallel.ResourceLock.SYSTEM_PROPERTIES,
              mode = ResourceAccessMode.READ_WRITE)
final class CglActorMessageTraceIntegrationTest {
    @Test
    void capturesBothAdmittedHandlerKindsWithoutPretendingToObserveTimers() throws Exception {
        final String key = "ores.runtime.cgl.handlerTrace";
        String previous = System.getProperty(key);
        System.setProperty(key, "true");
        try (var store = new SharedCodeImageStore(); var runtime = new ActorRuntime()) {
            var trace = runtime.cglHandlerTrace().orElseThrow();
            CountDownLatch hosted = new CountDownLatch(1);
            CountDownLatch sourced = new CountDownLatch(1);
            var host = runtime.<String>spawnShared(() -> (message, context) ->
                    hosted.countDown());

            var image = store.publish("cgl-handler-trace.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() {
                    return image;
                }
                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    return context.self()::id;
                }
                @Override public void receiveActor(String type,
                        ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) {
                    sourced.countDown();
                }
            };
            var source = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                    executor, "Worker");
            source.ready().get(3, TimeUnit.SECONDS);

            host.send("host");
            source.send("source");
            assertTrue(hosted.await(3, TimeUnit.SECONDS));
            assertTrue(sourced.await(3, TimeUnit.SECONDS));
            host.stop();
            source.stop();
            assertTrue(host.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(source.awaitTermination(3, TimeUnit.SECONDS));
            trace.sealAfterActorsStopped();
            var result = trace.snapshot();
            assertTrue(result.hasCompleteCapture(2));
            assertEquals(2, result.samples().size());
            assertEquals(0, result.dropped());
            assertEquals(0, result.invalid());
            assertTrue(result.samples().stream().anyMatch(x ->
                    x.actorId().equals(host.id().value()) &&
                    x.messageSequence() == 0 && !x.sourceActor()));
            assertTrue(result.samples().stream().anyMatch(x ->
                    x.actorId().equals(source.id().value()) &&
                    x.messageSequence() == 0 && x.sourceActor()));
            assertTrue(result.samples().stream().allMatch(x ->
                    x.exitNanos() - x.enterNanos() >= 0));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
