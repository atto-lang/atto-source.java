package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Local host transport proof, not a guest compiler or isolated-VM proof. */
@Timeout(15)
final class OresEnumSymbolActorMailTest {
    private static final String UNIT = "app/traffic.atto";
    private static final List<String> CASES = List.of("Red", "Green", "Yellow");

    @Test void nominalCaseIdentityIsImmutableAndNotNumeric() {
        OresEnumSymbol.Declaration light = OresEnumSymbol.declare(UNIT, "Light", CASES);
        OresEnumSymbol red = light.caseNamed("Red");
        OresEnumSymbol again = OresEnumSymbol.declare(
                UNIT, "Light", List.of("Yellow", "Red", "Green")).caseNamed("Red");
        assertNotSame(red, again, "no global pointer interning is required");
        assertEquals(red, again);
        assertEquals(red.hashCode(), again.hashCode());
        assertEquals("Light.Red", red.toString());
        assertNotEquals(red, 0);
        assertNotEquals(red, "Red");
        assertNotEquals(red, OresSymbol.process("Red"));
        assertNotEquals(red, light.caseNamed("Green"));
        assertNotEquals(red, OresEnumSymbol.declare(UNIT, "Status", CASES).caseNamed("Red"));
        assertNotEquals(red, OresEnumSymbol.declare("other/traffic.atto", "Light", CASES).caseNamed("Red"));
        assertNotEquals(red, OresEnumSymbol.declare(
                UNIT, "Light", List.of("Red", "Green", "Yellow", "Flashing")).caseNamed("Red"),
                "a changed closed schema must not silently reinterpret an old message");
    }

    @Test void closedDeclarationsRejectUnknownDuplicateOrMalformedCases() {
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light", List.of()));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light", List.of("Red", "Red")));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light", List.of("Red", "Bad Case")));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light", List.of("Red", "\u202eEvil")));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light", List.of("Red")).caseNamed("Green"));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare("other\u0000unit", "Light", CASES));
        assertThrows(IllegalArgumentException.class, () -> OresEnumSymbol.declare(UNIT, "Light.", CASES));
    }

    @Test void localFreezeRetainsSymbolAndDetachesMutableContainers() {
        var red = OresEnumSymbol.declare(UNIT, "Light", CASES).caseNamed("Red");
        assertSame(red, ActorRuntime.freeze(red), "immutable enum scalars are never encoded");
        var mutable = new ArrayList<Object>(List.of(red));
        Map<?, ?> frozenMap = assertInstanceOf(Map.class,
                ActorRuntime.freeze(Map.of("signal", mutable)));
        @SuppressWarnings("unchecked")
        List<Object> frozen = (List<Object>) assertInstanceOf(
                List.class, frozenMap.get("signal"));
        assertSame(red, frozen.getFirst());
        mutable.clear();
        assertEquals(1, frozen.size(), "container must be detached");
        assertThrows(UnsupportedOperationException.class, () -> frozen.add(red));
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new Object()));
    }

    @Test void actualTrustedPrivateAndSharedMailDeliverTheSameImmutableSymbol() throws Exception {
        var red = OresEnumSymbol.declare(UNIT, "Light", CASES).caseNamed("Red");
        var received = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var runtime = new ActorRuntime()) {
            var privateActor = runtime.<Object>spawnPrivate(() -> (value, context) -> {
                try {
                    assertSame(red, value, "local PRIVATE mail need not serialize a nominal scalar");
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    received.countDown();
                }
            });
            var sharedActor = runtime.<Object>spawnShared(() -> (value, context) -> {
                try {
                    assertSame(red, value, "local SHARED mail need not serialize a nominal scalar");
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    received.countDown();
                }
            });
            privateActor.ready().get(5, TimeUnit.SECONDS);
            sharedActor.ready().get(5, TimeUnit.SECONDS);
            privateActor.send(red);
            sharedActor.send(red);
            assertTrue(received.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("actor mail regression", failure.get());
            privateActor.stop();
            sharedActor.stop();
            assertTrue(privateActor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(sharedActor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(privateActor.failure().isEmpty());
            assertTrue(sharedActor.failure().isEmpty());
        }
    }

    @Test void untrustedIngressDeniesUnauthorisedEnumSymbolsEvenWhenNested() throws Exception {
        var red = OresEnumSymbol.declare(UNIT, "Light", CASES).caseNamed("Red");
        Method validator = ActorRuntime.class.getDeclaredMethod(
                "rejectUntrustedInboundCapabilities",
                Object.class, IdentityHashMap.class, int.class);
        validator.setAccessible(true);
        InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                () -> validator.invoke(null, List.of(Map.of("signal", red)), new IdentityHashMap<>(), 0));
        assertInstanceOf(SecurityException.class, failed.getCause());
    }
}
