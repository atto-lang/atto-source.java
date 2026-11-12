package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.NamespaceActorIngressV1;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Execute genuine source-declared namespace external init/receive through
 * compiler-generated ActorCodeExecutor. No handwritten Java ingress adapter.
 */
final class AttoNamespaceActorIngressExecutionV1Test {
    private static final String SOURCE = """
            use namespace counter;
            declare actor CounterActor;
            define state on actor CounterActor as {
                let int value : 0;
                let bool enabled : true;
            }
            external mtd (CounterActor c) init(): void {
                return;
            }
            external mtd (CounterActor c) receive(ActorMail<int> mail): void {
                c.send(c.add(mail.value));
                return;
            }
            critical mtd (CounterActor c) add(int delta): int {
                let int answer = 0;
                using state.write(critical |s| -> {
                    if s.enabled then
                        s.value = s.value + delta;
                        answer = s.value;
                    fi
                });
                return answer;
            }
            """;

    private static NamespaceActorIngressV1.Plan compile(
            SharedCodeImageStore store, String id, String source) {
        Ast.Program sourceAst = Parser.parse(source);
        var image = store.publish(id, sourceAst);
        return NamespaceActorIngressV1.compile(image, "counter@1", "CounterActor");
    }

    private static long sendAndRead(ActorRuntime.ActorRef<Object> actor, long delta)
            throws Exception {
        actor.sendAsync(delta).get(30, TimeUnit.SECONDS);
        var step = actor.outputs().next().get(30, TimeUnit.SECONDS);
        assertFalse(step.done(), "source receive must emit one checked result");
        return (Long) step.value().value();
    }

    @Test
    void tenThousandMessagesExecuteCompilerGeneratedExternalReceive()
            throws Exception {
        Ast.Program ast = Parser.parse(SOURCE);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(ast)).getMessage().contains("LOWERING_UNSUPPORTED"),
                "unsupported general guest ingress remains blocked");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("src/counter.atto", ast);
            var compiled = NamespaceActorIngressV1.compile(
                    image, "counter@1", "CounterActor");
            var actor = compiled.spawn(runtime, IsolatePolicy.developer());
            actor.ready().get(30, TimeUnit.SECONDS);
            for (int i = 1; i <= 10_000; i++)
                assertEquals((long) i, sendAndRead(actor, 1L),
                        "in-order private actor mailbox counter");
            assertEquals(10_000L, sendAndRead(actor, 0L));
            actor.stop();
            assertTrue(actor.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "source actor must exit without failure");
        }
    }

    @Test
    void twoDeclaredActorsShareOnlyCodeAndNeverTheirState() throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/counter.atto", SOURCE);
            var left = plan.spawn(runtime, IsolatePolicy.developer());
            var right = plan.spawn(runtime, IsolatePolicy.developer());
            left.ready().get(30, TimeUnit.SECONDS);
            right.ready().get(30, TimeUnit.SECONDS);
            for (int i = 1; i <= 128; i++) {
                assertEquals((long) i, sendAndRead(left, 1L));
                assertEquals(i * 2L, sendAndRead(right, 2L));
            }
            left.stop();
            right.stop();
            assertTrue(left.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(right.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(left.failure().isEmpty());
            assertTrue(right.failure().isEmpty());
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void overflowTerminatesOnlyCurrentActorAndNewGenerationBeginsWithFreshState()
            throws Exception {
        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var plan = compile(store, "src/counter.atto", SOURCE);
            var bad = plan.spawn(runtime, IsolatePolicy.developer());
            var survivor = plan.spawn(runtime, IsolatePolicy.developer());
            bad.ready().get(30, TimeUnit.SECONDS);
            survivor.ready().get(30, TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, sendAndRead(bad, Long.MAX_VALUE));
            bad.sendAsync(1L).get(30, TimeUnit.SECONDS);
            assertTrue(bad.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(bad.failure().isPresent());
            assertEquals(7L, sendAndRead(survivor, 7L));
            var replacement = plan.spawn(runtime, IsolatePolicy.developer());
            replacement.ready().get(30, TimeUnit.SECONDS);
            assertEquals(3L, sendAndRead(replacement, 3L));
            assertThrows(RuntimeException.class, () -> bad.send(1L),
                    "retired actors must never accept a new mailbox turn");
            survivor.stop();
            replacement.stop();
            assertTrue(survivor.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(replacement.awaitTermination(30, TimeUnit.SECONDS));
            assertTrue(survivor.failure().isEmpty());
            assertTrue(replacement.failure().isEmpty());
        }
    }

    @Test
    void sourceMutationsCannotForgeExternalIngressOrStateAuthority() {
        String[] mutated = {
                SOURCE.replace("external mtd (CounterActor c) init",
                        "pub external mtd (CounterActor c) init"),
                SOURCE.replace("external mtd (CounterActor c) receive",
                        "pub mtd (CounterActor c) receive"),
                SOURCE.replace("external mtd (CounterActor c) receive",
                        "define mtd (CounterActor c) receive"),
                SOURCE.replace("external mtd (CounterActor c) init(): void",
                        "external mtd (CounterActor c) init(int x): void"),
                SOURCE.replace("external mtd (CounterActor c) init(): void {",
                        "external mtd (CounterActor c) init(): void { c.add(9);"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.add(1))"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.add(mail.sender))"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.add(mail))"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.add(mail.value)"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.unknown(mail.value))"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.add(mail.value + 1))"),
                SOURCE.replace("c.send(c.add(mail.value))", "c.send(c.add(mail.value)); c.send(9)"),
                SOURCE.replace("ActorMail<int> mail", "ActorMail<bool> mail"),
                SOURCE.replace("ActorMail<int> mail", "ActorMail<borrow int> mail"),
                SOURCE.replace("critical mtd (CounterActor c) add",
                        "pub mtd (CounterActor c) add"),
                SOURCE.replace("declare actor CounterActor;", "declare actor OtherActor;")
        };
        for (String text : mutated) {
            assertNotEquals(SOURCE, text, "negative fixture must genuinely mutate the source");
            try (SharedCodeImageStore store = new SharedCodeImageStore()) {
                assertThrows(RuntimeException.class,
                        () -> compile(store, "src/counter.atto", text),
                        "unexpected accepted unsafe actor ingress mutation");
            }
        }
    }

    @Test
    void noImplicitOtherActorOrHostExecutionEntry() {
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            Ast.Program parsed = Parser.parse(SOURCE);
            var image = store.publish("src/counter.atto", parsed);
            assertThrows(IllegalArgumentException.class,
                    () -> NamespaceActorIngressV1.compile(image, "counter@1", "OtherActor"));
            assertThrows(IllegalArgumentException.class,
                    () -> NamespaceActorIngressV1.compile(image, "", "CounterActor"));
        }
    }
}
