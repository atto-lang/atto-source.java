package dev.oreslang.nodes;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ActorStateSupervisorLoans;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.SharedCodeImageStore;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the actual evaluator Map adapter that OresObject field reads
 * and writes use. A managed i64 has a single canonical runtime backing.
 */
final class ActorManagedI64FieldMapTest {
    @Test
    void boundI64FieldNeverMaintainsSecondMutableGuestMapCopy() throws Exception {
        try (var images = new SharedCodeImageStore();
             var runtime = new ActorRuntime(IsolatePolicy.developer())) {
            var image = images.publish("app/managed-field.ores",
                    Parser.parse("pub routine main(): void { return; }"));
            var supervisor = runtime.createSupervisor();
            var group = runtime.createActorGroup(supervisor);
            var mapped = new AtomicReference<Map<String,Object>>();
            var rawBacking = new AtomicReference<Map<String,Object>>();
            var latest = new AtomicLong(-1);
            var overflowDenied = new AtomicReference<Throwable>();
            ActorRuntime.ActorCodeExecutor executor = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String actorTypeName, ActorRuntime.ActorContext<Object> ctx) {
                    var region = ctx.createManagedPrimitiveStateRegion(8, 2).orElseThrow();
                    var field = region.declareOwnerLongField("balance", 3L);
                    var delta = region.declareOwnerLongField("delta", 4L);
                    var backing = new LinkedHashMap<String,Object>();
                    backing.put("label", "owned");
                    rawBacking.set(backing);
                    var slots = new LinkedHashMap<
                            String, ActorStateSupervisorLoans.LongField>();
                    slots.put("balance", field);
                    slots.put("delta", delta);
                    mapped.set(new OresEvalRootNode.ActorManagedI64Fields(
                            region, backing, slots,
                            java.util.List.of("balance", "label", "delta")));
                    return ctx.self()::id;
                }

                @Override public void receiveActor(
                        String actorTypeName, ActorRuntime.ActorOwnedGuestState state,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> ctx) {
                    Map<String,Object> fields = mapped.get();
                    switch (mail.value().toString()) {
                        case "read" -> {
                            assertTrue(fields.containsKey("balance"));
                            assertEquals(3, fields.size());
                            assertEquals(3, fields.entrySet().size());
                            assertEquals(java.util.List.of("balance", "label", "delta"),
                                    fields.entrySet().stream()
                                            .map(Map.Entry::getKey).toList(),
                                    "the original actor declaration order must be stable");
                            assertEquals(4L, fields.get("delta"));
                            latest.set(((Number) fields.get("balance")).longValue());
                        }
                        case "write" -> {
                            assertEquals(3L, fields.put("balance", 10L));
                            assertEquals(10L, fields.get("balance"));
                            assertEquals("owned", fields.get("label"));
                        }
                        case "overflow" -> {
                            try {
                                fields.put("balance", new BigInteger(
                                        "9223372036854775808"));
                            } catch (Throwable failure) {
                                overflowDenied.set(failure);
                            }
                        }
                        default -> throw new IllegalArgumentException(mail.value().toString());
                    }
                }
            };
            try {
                var actor = group.spawnCodeWorker(
                        ActorRuntime.ActorKind.PRIVATE,
                        IsolatePolicy.developer(), executor, "Worker");
                actor.ready().get(5, TimeUnit.SECONDS);
                assertFalse(rawBacking.get().containsKey("balance"),
                        "the guest object's plain map must not retain an i64 mirror");
                assertFalse(rawBacking.get().containsKey("delta"));
                assertThrows(SecurityException.class,
                        () -> mapped.get().get("balance"),
                        "untrusted host cannot read the actor's typed field");
                actor.send("read");
                actor.drain().get(5, TimeUnit.SECONDS);
                assertEquals(3L, latest.get());
                actor.send("write");
                actor.drain().get(5, TimeUnit.SECONDS);
                actor.send("read");
                actor.drain().get(5, TimeUnit.SECONDS);
                assertEquals(10L, latest.get());
                assertFalse(rawBacking.get().containsKey("balance"));
                actor.send("overflow");
                actor.drain().get(5, TimeUnit.SECONDS);
                assertInstanceOf(ArithmeticException.class, overflowDenied.get());
                actor.send("read");
                actor.drain().get(5, TimeUnit.SECONDS);
                assertEquals(10L, latest.get());
                assertTrue(actor.cancel());
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertThrows(SecurityException.class,
                        () -> mapped.get().get("balance"),
                        "STOP must not make a managed cell readable after retirement");
            } finally {
                group.close();
            }
        }
    }
}
