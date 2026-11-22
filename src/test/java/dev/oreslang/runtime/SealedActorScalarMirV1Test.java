package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real ActorRuntime turn execution of the intentionally tiny checked MIR. */
final class SealedActorScalarMirV1Test {
    private static final String SOURCE = """
            define actor Counter as
              state { let int count = 0; }
              critical increment(): void {
                self.handle_state(|mut s| -> {
                  s.count = s.count + 1;
                });
                return;
              }
              receive(ActorMail<int> mail): void {
                self.increment();
                return;
              }
            end
            """;

    @Test
    void parserBuildsCriticalAstAndScalarMirExecutesOnActorOwnedState()
            throws Exception {
        var plan = dev.oreslang.compiler.OresCompiler.compileSealedActorScalarI64(SOURCE);
        assertEquals("Counter", plan.actorType());
        assertEquals("count", plan.field());
        assertEquals(0L, plan.initialValue());
        // The opt-in MIR does not promote arbitrary critical source through
        // the ordinary compiler/evaluator without checked ownership proofs.
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(SOURCE));

        var events = new ArrayList<Long>();
        var received = new CountDownLatch(3);
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var code = store.publish("atto/sealed-counter", plan.program());
            var executor = plan.executor(code, value -> {
                synchronized (events) {
                    events.add(value);
                }
                received.countDown();
            });
            var actor = runtime.spawnCodeActor(
                    plan.actorKind(), IsolatePolicy.developer(),
                    executor, plan.actorType());
            assertNull(actor.ready().join());
            actor.send(1L);
            actor.send(2L);
            actor.send(3L);
            assertTrue(received.await(5, TimeUnit.SECONDS),
                    "checked source actor must execute three real mailbox turns");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "source actor failure: "
                    + actor.failure());
        }
        synchronized (events) {
            assertEquals(List.of(1L, 2L, 3L), events,
                    "state must survive across source actor turns in FIFO order");
        }
    }

    @Test
    void sourcePlanCannotBeReboundToLookalikeProgram() throws Exception {
        var plan = SealedActorScalarMirV1.lower(SOURCE);
        Ast.Program duplicate = Parser.parse(SOURCE);
        assertNotSame(plan.program(), duplicate);
        try (var store = new SharedCodeImageStore()) {
            var wrong = store.publish("atto/lookalike-counter", duplicate);
            assertThrows(SecurityException.class,
                    () -> plan.executor(wrong, ignored -> { }));
        }
    }

    @Test
    void unsafeMutationsDoNotObtainExecutableActorMir() {
        for (String bad : List.of(
                SOURCE.replace("critical increment", "increment"),
                SOURCE.replace("|mut s|", "|s|"),
                SOURCE.replace("s.count + 1", "s.count + 2"),
                SOURCE.replace("s.count = s.count + 1", "s.count = 1"),
                SOURCE.replace("self.increment();", "self.end();"),
                SOURCE.replace("ActorMail<int>", "ActorMail<String>"),
                SOURCE.replace("return;\n  }\nend",
                        "await 1;\n    return;\n  }\nend"),
                SOURCE + "define actor Extra as end")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SealedActorScalarMirV1.lower(bad),
                    "unverified source must not mint a runnable scalar actor MIR");
        }
    }

    @Test
    void overflowFailsAtomicallyAndCannotWrapActorState() throws Exception {
        var overflowSource = SOURCE.replace("let int count = 0;",
                "let int count = 9223372036854775807;");
        var plan = SealedActorScalarMirV1.lower(overflowSource);
        var events = new ArrayList<Long>();
        try (var store = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = store.publish("atto/overflow-counter", plan.program());
            var actor = runtime.spawnCodeActor(plan.actorKind(),
                    IsolatePolicy.developer(),
                    plan.executor(image, events::add), plan.actorType());
            assertNull(actor.ready().join());
            actor.send(1L);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "checked addition overflow must not leave a running actor");
            assertTrue(actor.failure().isPresent(),
                    "arithmetic failure must reach supervisor failure reporting");
            assertTrue(events.isEmpty(), "overflow cannot publish a wrapped value");
        }
    }
}
