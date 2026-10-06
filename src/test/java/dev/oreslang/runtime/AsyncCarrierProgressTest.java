package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncCarrierProgressTest {
    @Test void nestedOuterCompletesWhileInnerIsPendingOnOneControlCarrier() throws Exception {
        proveProgress("Future<int>", "spawned.result", """
            val Future<Future<int>> outer = launch();
            val Future<int> inner = await outer;
            val ready = inner.is_done();
            val peer = await quick();
            val answer = await inner;
            stdio.println(ready);
            stdio.println(peer);
            stdio.println(answer);
            """, "producer\nfalse\n42\n10\n");
    }

    @Test void pendingExplicitAwaitReleasesTheOnlyControlCarrier() throws Exception {
        proveProgress("int", "await spawned.result", """
            val Future<int> pending = launch();
            val peer = await quick();
            val answer = await pending;
            stdio.println(peer);
            stdio.println(answer);
            """, "producer\n42\n10\n");
    }

    private static void proveProgress(String returnType, String returned, String main, String expected) throws Exception {
        OresVM vm = OresVM.dedicated(new ActorRuntime.DispatcherConfig(1, 1, 1, 16, 64));
        ActorRuntime observer = vm.newActorRuntime(IsolatePolicy.developer(), Runnable::run);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream output = new OutputStream() {
            @Override public void write(int value) {
                bytes.write(value);
                if (value == '\n' && entered.getCount() != 0) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                }
            }
        };
        String program = """
            actor fnc worker(): int { stdio.println("producer"); return 10; }
            async fnc quick(): int { return 42; }
            async fnc launch(): %s {
              val spawned = spawn worker();
              return %s;
            }
            pub routine main(): void { %s return; }
            """.formatted(returnType, returned, main);
        ExecutorService host = Executors.newSingleThreadExecutor();
        try {
            Future<?> evaluation = host.submit(() -> {
                try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                        .option("engine.WarnInterpreterOnly", "false").out(output)
                        .arguments(OresLanguage.ID, vm.bindApplicationArguments(new String[0], null)).build()) {
                    context.eval(Source.newBuilder(OresLanguage.ID, program, "one-carrier.ores")
                            .mimeType(OresLanguage.MIME_TYPE).build());
                } catch (Exception e) { throw new CompletionException(e); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "actor must be held pending");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                // launch yields/completes, and quick finishes on the same sole carrier.
                while (observer.controlDispatcherStats().completedActorTurns() < 2 && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertTrue(observer.controlDispatcherStats().completedActorTurns() >= 2,
                        "quick must finish while actor.result remains pending");
                assertFalse(evaluation.isDone());
                assertEquals(1, observer.controlDispatcherStats().largestPoolSize());
            } finally { release.countDown(); }
            evaluation.get(5, TimeUnit.SECONDS);
            assertEquals(expected, bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            release.countDown();
            host.shutdownNow();
            observer.close();
            vm.shutdownNow();
        }
    }
}
