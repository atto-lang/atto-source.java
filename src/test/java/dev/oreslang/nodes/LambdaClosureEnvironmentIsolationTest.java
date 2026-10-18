package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercise the actual runtime Env implementation, not a copied mock of its
 * capture projection. The reflection is test-only: Env remains private.
 */
final class LambdaClosureEnvironmentIsolationTest {
    private static final class EnvAccess {
        final Class<?> type;
        final Constructor<?> ctor;
        final Method define, lookup, assign, reserve, initialize, discardReservation, project;
        final Field parent, slots, constructingObject;

        EnvAccess() throws Exception {
            type = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Env");
            ctor = type.getDeclaredConstructor(type);
            ctor.setAccessible(true);
            define = type.getDeclaredMethod("define", String.class, Object.class, Ast.BindingKind.class);
            lookup = type.getDeclaredMethod("lookup", String.class);
            assign = type.getDeclaredMethod("assign", String.class, Object.class);
            reserve = type.getDeclaredMethod("reserve", String.class, Ast.BindingKind.class);
            initialize = type.getDeclaredMethod("initialize", String.class, Object.class);
            discardReservation = type.getDeclaredMethod("discardReservation", String.class);
            project = type.getDeclaredMethod("snapshotCaptures", Set.class);
            for (Method m : new Method[] {
                    define, lookup, assign, reserve, initialize, discardReservation, project
            }) m.setAccessible(true);
            parent = type.getDeclaredField("parent");
            slots = type.getDeclaredField("slots");
            constructingObject = type.getDeclaredField("constructingObject");
            for (Field f : new Field[] {parent, slots, constructingObject}) f.setAccessible(true);
        }

        Object frame(Object parentFrame) throws Exception { return ctor.newInstance(parentFrame); }
        void put(Object frame, String name, Object value, Ast.BindingKind kind) throws Exception {
            define.invoke(frame, name, value, kind);
        }
        Object capture(Object frame, String... names) throws Exception {
            return project.invoke(frame, Set.of(names));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> bindings(Object frame) throws Exception {
            return (Map<String, Object>) slots.get(frame);
        }
    }

    @Test void unusedReceiverAndCapabilityNeverReachTheClosureFrame() throws Exception {
        EnvAccess a = new EnvAccess();
        Object root = a.frame(null);
        Object receiver = new Object();
        Object capability = new Object();
        a.put(root, "self", receiver, Ast.BindingKind.VAL);
        a.put(root, "secretCapability", capability, Ast.BindingKind.VAL);
        a.put(root, "used", 17, Ast.BindingKind.VAL);
        Object frame = a.frame(root);
        Object closure = a.capture(frame, "used");

        assertNull(a.parent.get(closure), "a closure must not keep its source Env chain");
        assertNull(a.constructingObject.get(closure), "constructor receiver authority must not propagate");
        assertEquals(Set.of("used"), a.bindings(closure).keySet());
        assertEquals(17, a.lookup.invoke(closure, "used"));
        assertFalse(a.bindings(closure).containsKey("self"));
        assertFalse(a.bindings(closure).containsKey("secretCapability"));
    }

    @Test void nearestShadowedSlotWinsAndMutableCaptureRetainsSlotIdentity() throws Exception {
        EnvAccess a = new EnvAccess();
        Object root = a.frame(null);
        a.put(root, "counter", 111, Ast.BindingKind.LET);
        Object inner = a.frame(root);
        a.put(inner, "counter", 7, Ast.BindingKind.LET);
        Object closure = a.capture(inner, "counter");
        assertSame(a.bindings(inner).get("counter"), a.bindings(closure).get("counter"));
        assertNotSame(a.bindings(root).get("counter"), a.bindings(closure).get("counter"));
        a.assign.invoke(closure, "counter", 9);
        assertEquals(9, a.lookup.invoke(inner, "counter"));
        assertEquals(111, a.lookup.invoke(root, "counter"));
    }

    @Test void recursiveReservationIsSharedWithClosureAndCanRollbackAtomically() throws Exception {
        EnvAccess a = new EnvAccess();
        Object root = a.frame(null);
        a.reserve.invoke(root, "recur", Ast.BindingKind.VAL);
        Object closure = a.capture(root, "recur");
        Object callable = new Object();
        a.initialize.invoke(root, "recur", callable);
        assertSame(callable, a.lookup.invoke(closure, "recur"),
                "recursive closure must share the reserved self Slot");

        Object failed = a.frame(null);
        a.reserve.invoke(failed, "broken", Ast.BindingKind.VAL);
        a.discardReservation.invoke(failed, "broken");
        assertFalse(a.bindings(failed).containsKey("broken"),
                "failed recursive initializer must not leave a MISSING reservation");
    }

    @Test void explicitSelfCaptureStillRequiresAReferencedSlot() throws Exception {
        EnvAccess a = new EnvAccess();
        Object root = a.frame(null);
        Object receiver = new Object();
        a.put(root, "self", receiver, Ast.BindingKind.VAL);
        Object closure = a.capture(root, "self");
        assertEquals(Set.of("self"), a.bindings(closure).keySet());
        assertSame(receiver, a.lookup.invoke(closure, "self"));
        assertNull(a.parent.get(closure));
    }
}
