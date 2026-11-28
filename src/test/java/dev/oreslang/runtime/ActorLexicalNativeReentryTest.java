package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM-only white-box regression for the source evaluator's private Env/Slot
 * implementation. Reflection lives in the test, never in Oreslang runtime or
 * compiler lowering. The assertions run inside a real actor-owned arena turn.
 */
@Timeout(30)
final class ActorLexicalNativeReentryTest {
    @Test
    void duplicateBindingsDoNotAllocateAndManagedFallbackCanReenterTheSameNativeCell()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 128);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    ActorNativeArena arena = ActorRuntime.currentNativeArena();
                    assertNotNull(arena, "shared actor must own its native arena");

                    Class<?> envType = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Env");
                    Constructor<?> constructor = envType.getDeclaredConstructor(envType);
                    constructor.setAccessible(true);
                    Object env = constructor.newInstance((Object) null);

                    Method define = Arrays.stream(envType.getDeclaredMethods())
                            .filter(method -> method.getName().equals("define")
                                    && method.getParameterCount() == 3)
                            .findFirst().orElseThrow();
                    define.setAccessible(true);
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object let = Enum.valueOf((Class<? extends Enum>) define.getParameterTypes()[2],
                            "LET");

                    Method assign = envType.getDeclaredMethod("assign", String.class, Object.class);
                    Method lookup = envType.getDeclaredMethod("lookup", String.class);
                    assign.setAccessible(true);
                    lookup.setAccessible(true);

                    Field slotsField = envType.getDeclaredField("slots");
                    slotsField.setAccessible(true);

                    define.invoke(env, "value", 41L, let);
                    @SuppressWarnings("unchecked")
                    Map<String, ?> slots = (Map<String, ?>) slotsField.get(env);
                    Object slot = slots.get("value");
                    Field nativeCell = slot.getClass().getDeclaredField("nativeCell");
                    nativeCell.setAccessible(true);
                    Object initialCell = nativeCell.get(slot);
                    assertNotNull(initialCell);
                    long initialAllocated = arena.allocatedBytes();

                    InvocationTargetException duplicate = assertThrows(
                            InvocationTargetException.class,
                            () -> define.invoke(env, "value", 99L, let));
                    assertInstanceOf(IllegalArgumentException.class, duplicate.getCause());
                    assertEquals(41L, lookup.invoke(env, "value"));
                    assertEquals(initialAllocated, arena.allocatedBytes(),
                            "rejected duplicate definition must not allocate its unused native cell");

                    Object managedOnly = new Object();
                    assign.invoke(env, "value", managedOnly);
                    assertSame(managedOnly, lookup.invoke(env, "value"));
                    assertSame(initialCell, nativeCell.get(slot),
                            "native cell should be retained but its payload retired");

                    assign.invoke(env, "value", 42L);
                    assertEquals(42L, lookup.invoke(env, "value"));
                    assertSame(initialCell, nativeCell.get(slot));
                    assertEquals(initialAllocated, arena.allocatedBytes(),
                            "managed -> native reentry must reuse the original native slot");

                    define.invoke(env, "initiallyManaged", new Object(), let);
                    Object newSlot = slots.get("initiallyManaged");
                    assertNull(nativeCell.get(newSlot));
                    assign.invoke(env, "initiallyManaged", 7L);
                    assertEquals(7L, lookup.invoke(env, "initiallyManaged"));
                    assertNotNull(nativeCell.get(newSlot),
                            "an initially managed binding must admit native lowering later");

                    // A managed-only slot must not silently accept foreign
                    // arena memory before it acquires its first native cell.
                    define.invoke(env, "stillManaged", new Object(), let);
                    AtomicReference<Long> foreignBudget = new AtomicReference<>(0L);
                    try (ActorNativeArena foreignArena =
                                 new ActorNativeArena(4096, () -> {},
                                         bytes -> {
                                             foreignBudget.updateAndGet(n -> n + bytes);
                                             return () -> foreignBudget.updateAndGet(n -> n - bytes);
                                         })) {
                        Object foreignObject = foreignArena.fields(
                                java.util.List.of("v"), new Object());
                        InvocationTargetException denied = assertThrows(
                                InvocationTargetException.class,
                                () -> assign.invoke(env, "stillManaged", foreignObject));
                        assertInstanceOf(SecurityException.class, denied.getCause());
                        assertNull(nativeCell.get(slots.get("stillManaged")));
                    }
                    assertEquals(0L, foreignBudget.get());

                    // Repeated transitions should reuse one native value cell,
                    // not accumulate actor-lifetime slabs or stay managed.
                    for (int i = 0; i < 100; i++) {
                        assign.invoke(env, "value", new Object());
                        assign.invoke(env, "value", (long) i);
                    }
                    assertSame(initialCell, nativeCell.get(slot));
                    assertEquals(99L, lookup.invoke(env, "value"));

                    // Env.snapshot intentionally shares Slot objects. A
                    // compiler must not automatically call ValueCell.close()
                    // merely when one lexical Env frame returns: the snapshot
                    // can still be used by a captured callback/continuation.
                    Method snapshot = envType.getDeclaredMethod("snapshot");
                    snapshot.setAccessible(true);
                    Object copiedEnv = snapshot.invoke(env);
                    @SuppressWarnings("unchecked")
                    Map<String, ?> copiedSlots =
                            (Map<String, ?>) slotsField.get(copiedEnv);
                    assertSame(slot, copiedSlots.get("value"));
                    assertEquals(99L, lookup.invoke(copiedEnv, "value"));
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    finished.countDown();
                    context.self().stop();
                }
            });
            actor.send(1);
            assertTrue(finished.await(10, TimeUnit.SECONDS), "actor test turn should complete");
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                fail("native lexical storage regression", failure.get());
            }
        }
    }
}
