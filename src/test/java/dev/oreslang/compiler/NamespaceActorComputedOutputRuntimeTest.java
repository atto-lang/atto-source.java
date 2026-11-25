package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Physical ActorRuntime checked output overflow and sibling isolation. */
@Timeout(20)
final class NamespaceActorComputedOutputRuntimeTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/checked-computed-output-readback.atto"));
    }

    @Test
    void checkedOutputOverflowFailsActorWithoutPublishingFalseResultOrKillingSibling()
            throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var code = NamespaceActorCriticalIrCompiler.compileForVerifiedMain(
                    images.publish("checked-output/overflow.atto", Parser.parse(source())));
            assertTrue(NamespaceActorCriticalIrCompiler.emitsScalarOutput(code));
            var bad = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, code, "Meter");
            var good = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, code, "Meter");
            bad.ready().get(3, TimeUnit.SECONDS);
            good.ready().get(3, TimeUnit.SECONDS);

            bad.send(Long.MAX_VALUE); // checked +1 output must overflow.
            assertThrows(ExecutionException.class,
                    () -> bad.done().get(5, TimeUnit.SECONDS));
            assertTrue(bad.failure().orElseThrow() instanceof ArithmeticException,
                    "actor output arithmetic must fail before emission");
            assertThrows(ExecutionException.class,
                    () -> bad.outputs().next().get(3, TimeUnit.SECONDS),
                    "failed actor must not publish a falsely successful output");

            good.send(5L);
            good.drain().get(3, TimeUnit.SECONDS);
            var emitted = good.outputs().next().get(3, TimeUnit.SECONDS);
            assertFalse(emitted.done());
            assertEquals(good.id(), emitted.value().actorId());
            assertEquals(0L, emitted.value().sequence());
            assertEquals(6L, emitted.value().value());
            assertEquals(1L, NamespaceActorCriticalIrCompiler
                    .receiptAfterDrain(code, good).processed());
            assertTrue(good.failure().isEmpty());
            good.stop();
            good.done().get(3, TimeUnit.SECONDS);
        }
    }
}
