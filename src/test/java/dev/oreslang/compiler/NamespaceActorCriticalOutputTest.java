package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strictly compiler-checked actor-local scalar output, not general guest reply
 * lowering. The source performs a critical read and the physical actor turn
 * emits an immutable value through the bounded runtime output stream.
 */
@Timeout(20)
final class NamespaceActorCriticalOutputTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/critical-ir-actor-output.atto"));
    }

    private static ActorRuntime.ActorCodeExecutor compile(
            SharedCodeImageStore store, String source, String path) {
        return NamespaceActorCriticalIrCompiler.compile(
                store.publish(path, Parser.parse(source)));
    }

    @Test
    void sourceCriticalReadEmitsComputedScalarFromEachActorOwnTurn() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var compiled = compile(store, source(), "critical-output/positive.atto");
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, compiled, "Probe");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, compiled, "Probe");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);

            a.send(3L);
            a.send(4L);
            b.send(11L);
            a.drain().get(3, TimeUnit.SECONDS);
            b.drain().get(3, TimeUnit.SECONDS);

            var first = a.outputs().next().get(3, TimeUnit.SECONDS);
            var second = a.outputs().next().get(3, TimeUnit.SECONDS);
            var other = b.outputs().next().get(3, TimeUnit.SECONDS);
            assertFalse(first.done());
            assertFalse(second.done());
            assertFalse(other.done());
            assertEquals(a.id(), first.value().actorId());
            assertEquals(a.id(), second.value().actorId());
            assertEquals(b.id(), other.value().actorId());
            assertEquals(3L, first.value().value());
            assertEquals(7L, second.value().value(),
                    "a source-computed accumulated value, not a literal input echo");
            assertEquals(11L, other.value().value());
            assertTrue(a.failure().isEmpty());
            assertTrue(b.failure().isEmpty());

            // Independent compiler-private diagnostic: output came from
            // each actor's own state and neither sibling can change it.
            var aReceipt = NamespaceActorCriticalIrCompiler.receiptAfterDrain(compiled, a);
            var bReceipt = NamespaceActorCriticalIrCompiler.receiptAfterDrain(compiled, b);
            assertEquals(7L, aReceipt.require("received"));
            assertEquals(9993L, aReceipt.require("budget"));
            assertEquals(11L, bReceipt.require("received"));
            assertEquals(9989L, bReceipt.require("budget"));
            assertThrows(UnsupportedOperationException.class,
                    () -> aReceipt.fields().put("received", 123L));

            a.stop();
            b.stop();
            a.done().get(3, TimeUnit.SECONDS);
            b.done().get(3, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> NamespaceActorCriticalIrCompiler.receiptAfterDrain(compiled, a));
        }
    }

    @Test
    void checkedReadOutputCannotBypassCriticalStateOrForgeIngress() throws Exception {
        String original = source();
        String[] invalid = {
                original.replace("p.send(p.current());", "p.send(p.state.received);"),
                original.replace("p.send(p.current());", "p.send(mail.value);"),
                original.replace("p.send(p.current());", "p.send(p.unknown());"),
                original.replace("p.send(p.current());", "p.send(p.add(mail.value));"),
                original.replace("p.send(p.current());", "p.send(p.current() + 1);"),
                original.replace("p.send(p.current());",
                        "p.send(p.current()); p.send(p.current());"),
                original.replace("p.send(p.current());",
                        "p.send(p.current()); p.add(mail.value);"),
                original.replace("p.send(p.current());", "other.send(p.current());"),
                original.replace("return p.state.received;",
                        "return p.state.not_a_field;"),
                original.replace("return p.state.received;",
                        "p.state.received = 999; return p.state.received;"),
                original.replace("critical mtd (Probe p) current()",
                        "external mtd (Probe p) current()"),
                original.replace("return p.state.received;", "return 123;")
        };
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            for (int i = 0; i < invalid.length; i++) {
                final int index = i;
                assertThrows(RuntimeException.class, () ->
                        compile(store, invalid[index], "critical-output/reject-" + index + ".atto"),
                        "must reject source output forgery or critical state bypass " + index);
            }
        }
    }

    @Test
    void oldActorIngressWithoutOutputRemainsSupported() throws Exception {
        String original = source().replace("p.send(p.current());", "")
                .replace("critical mtd (Probe p) current(): int {\n    return p.state.received;\n}\n", "");
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var compiled = compile(store, original, "critical-output/backward-compatible.atto");
            var actor = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, compiled, "Probe");
            actor.ready().get(3, TimeUnit.SECONDS);
            actor.send(5L);
            actor.drain().get(3, TimeUnit.SECONDS);
            assertEquals(5L, NamespaceActorCriticalIrCompiler
                    .receiptAfterDrain(compiled, actor).require("received"));
            actor.stop();
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }
}
