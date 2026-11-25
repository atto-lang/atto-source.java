package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime mailbox/channel execution of a compiler-checked native
 * Atto receive method, never a Java behavior supplied as guest code.
 */
@Timeout(25)
final class NamespaceActorCheckedOutputTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/critical-output-receive.atto"));
    }

    private static ActorRuntime.ActorCodeExecutor compile(
            SharedCodeImageStore images, String text, String unit) {
        return NamespaceActorCriticalIrCompiler.compile(
                images.publish(unit, Parser.parse(text)));
    }

    @Test
    void checkedAttoReceiveEmitsOwnedScalarOnTwoSeparateOutputChannels() throws Exception {
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var code = compile(images, source(), "checked-output/two-actors.atto");
            var a = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, code, "Probe");
            var b = runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, code, "Probe");
            a.ready().get(3, TimeUnit.SECONDS);
            b.ready().get(3, TimeUnit.SECONDS);
            assertNotEquals(a.id(), b.id());

            a.send(3L);
            a.send(7L);
            b.send(11L);
            a.drain().get(5, TimeUnit.SECONDS);
            b.drain().get(5, TimeUnit.SECONDS);

            var aFirst = a.outputs().next().get(3, TimeUnit.SECONDS);
            var aSecond = a.outputs().next().get(3, TimeUnit.SECONDS);
            var bFirst = b.outputs().next().get(3, TimeUnit.SECONDS);
            assertFalse(aFirst.done());
            assertFalse(aSecond.done());
            assertFalse(bFirst.done());
            assertEquals(a.id(), aFirst.value().actorId());
            assertEquals(a.id(), aSecond.value().actorId());
            assertEquals(b.id(), bFirst.value().actorId());
            assertEquals(0L, aFirst.value().sequence());
            assertEquals(1L, aSecond.value().sequence());
            assertEquals(0L, bFirst.value().sequence());
            assertEquals(3L, aFirst.value().value());
            assertEquals(7L, aSecond.value().value());
            assertEquals(11L, bFirst.value().value());

            var stateA = NamespaceActorCriticalIrCompiler.receiptAfterDrain(code, a);
            var stateB = NamespaceActorCriticalIrCompiler.receiptAfterDrain(code, b);
            assertEquals(10L, stateA.require("received"));
            assertEquals(9990L, stateA.require("budget"));
            assertEquals(11L, stateB.require("received"));
            assertEquals(9989L, stateB.require("budget"));
            assertEquals(2L, stateA.processed());
            assertEquals(1L, stateB.processed());
            assertTrue(a.failure().isEmpty());
            assertTrue(b.failure().isEmpty());

            a.stop();
            b.stop();
            a.done().get(3, TimeUnit.SECONDS);
            b.done().get(3, TimeUnit.SECONDS);
            assertThrows(SecurityException.class,
                    () -> NamespaceActorCriticalIrCompiler.receiptAfterDrain(code, a));
        }
    }

    @Test
    void rejectsSpoofedOutputReceiverPayloadAndOrdering() throws Exception {
        String verified = source();
        String[] invalid = {
                verified.replace("p.send(mail.value);", "other.send(mail.value);"),
                verified.replace("p.send(mail.value);", "p.send(3);"),
                verified.replace("p.send(mail.value);", "p.send(mail);"),
                verified.replace("p.send(mail.value);", "p.send(mail.untrusted);"),
                verified.replace("p.send(mail.value);", "p.send(p.state.received);"),
                verified.replace("p.send(mail.value);", "p.send(mail.value, 1);"),
                verified.replace("p.send(mail.value);", "p.send(mail.value); p.send(mail.value);"),
                verified.replace("p.debit(mail.value);\n    p.send(mail.value);",
                        "p.send(mail.value);\n    p.debit(mail.value);"),
                verified.replace("p.send(mail.value);", "return; p.send(mail.value);"),
                verified.replace("p.send(mail.value);", "p.output(mail.value);")
        };
        try (SharedCodeImageStore images = new SharedCodeImageStore()) {
            for (int i = 0; i < invalid.length; i++) {
                final int variant = i;
                assertThrows(RuntimeException.class,
                        () -> compile(images, invalid[variant],
                                "checked-output/negative-" + variant + ".atto"),
                        "unverified actor output variant " + variant);
            }
        }
    }

    @Test
    void ordinaryUnverifiedGuestCompilerDoesNotGainOutputAuthority() throws Exception {
        var rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(rejected.getMessage().contains("LOWERING_UNSUPPORTED"),
                rejected.getMessage());
    }
}
