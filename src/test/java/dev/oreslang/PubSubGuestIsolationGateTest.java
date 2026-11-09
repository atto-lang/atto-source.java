package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real AttoLang Graal Polyglot guest tests, not host ActorEventBus mocks.
 * These authored tests establish nothing until an exact-head workflow
 * compiles and executes them with individually verified Surefire receipts.
 *
 * A process-global named group directory, stale actor identity, or leaked
 * topic subscriber would cause independent contexts using identical guest
 * source/names to interfere. Keep unrelated actor lifecycles separate.
 */
@Timeout(100)
final class PubSubGuestIsolationGateTest {
    private static final String POSITIVE = "actor-pubsub-v1.atto";
    private static final String MULTIGROUP = "actor-pubsub-multigroup-v1.atto";

    @Test
    void twoConcurrentGraalContextsWithSameGroupAndTopicNamesStayIsolated()
            throws Exception {
        final String program = fixture(POSITIVE);
        OresCompiler.parseAndTypeCheck(program);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> left = workers.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent guest start timed out");
                }
                return run(program, "pubsub-context-left.atto");
            });
            Future<String> right = workers.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent guest start timed out");
                }
                return run(program, "pubsub-context-right.atto");
            });
            start.countDown();
            assertEquals("subscribed|p-1", left.get(45, TimeUnit.SECONDS));
            assertEquals("subscribed|p-1", right.get(45, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS),
                    "a guest actor or runtime thread remained after context disposal");
        }
    }

    @Test
    void deniedActorAuthorizationDoesNotPoisonFreshPositiveGuestContext()
            throws Exception {
        PolyglotException rejected = assertThrows(
                PolyglotException.class, () -> run(
                        fixture("actor-pubsub-reject-actor-authority.atto"),
                        "pubsub-reject-first.atto"));
        assertTrue(rejected.getMessage().contains(
                        "actor code may resolve an existing Topic<T> but cannot define one"),
                rejected.getMessage());
        String positive = fixture(POSITIVE);
        assertEquals("subscribed|p-1",
                run(positive, "pubsub-positive-after-rejection.atto"));
        assertEquals("subscribed|p-1",
                run(positive, "pubsub-positive-second-context.atto"));
    }

    @Test
    void multiGroupLeaveAndRejoinScenariosRemainContextLocal()
            throws Exception {
        String source = fixture(MULTIGROUP);
        OresCompiler.parseAndTypeCheck(source);
        // Distinct contexts must never reuse a predecessor's ActorGroup
        // membership grants or topic queues under matching human names.
        for (int i = 0; i < 3; i++) {
            assertEquals("ready-b|b-still-live",
                    run(source, "pubsub-multigroup-fresh-" + i + ".atto"));
        }
    }

    private static String fixture(String fileName) throws Exception {
        return Files.readString(Path.of("contracts", "v1", "fixtures", fileName),
                StandardCharsets.UTF_8);
    }

    private static String run(String program, String fileName) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, fileName)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
