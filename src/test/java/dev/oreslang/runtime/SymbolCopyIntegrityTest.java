package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Reconciled from draft #1651 onto current copy/admission semantics. */
class SymbolCopyIntegrityTest {
    @Test void frozenMapRejectsDistinctHostKeysWithEqualLogicalSymbolIdentity() {
        Map<Object, Object> adversarial = new IdentityHashMap<>();
        adversarial.put(OresSymbol.process("café"), 1L);
        adversarial.put(OresSymbol.process("cafe\u0301"), 2L);
        assertEquals(2, adversarial.size());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(List.of(adversarial)));
        assertTrue(failure.getMessage().contains("duplicate logical"));
    }

    @Test void frozenSetRejectsLogicalSymbolCollision() {
        Set<Object> adversarial = Collections.newSetFromMap(new IdentityHashMap<>());
        adversarial.add(OresSymbol.process("café"));
        adversarial.add(OresSymbol.process("cafe\u0301"));
        assertEquals(2, adversarial.size());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(adversarial));
        assertTrue(failure.getMessage().contains("duplicate logical element"));
    }

    @Test void privateMailboxDetachmentRejectsLogicalSymbolCollision()
            throws ReflectiveOperationException {
        Map<Object, Object> adversarial = new IdentityHashMap<>();
        adversarial.put(OresSymbol.process("foo"), 1L);
        adversarial.put(OresSymbol.process("foo"), 2L);
        Method copier = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copier.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> copier.invoke(null, List.of(adversarial)));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("duplicate logical"));
    }

    @Test void sharedFreezeReusesImmutableSymbolWhilePrivateCopyDetaches()
            throws ReflectiveOperationException {
        OresSymbol original = OresSymbol.process("ready");
        // Trusted SHARED actor transport may reuse a deeply immutable value.
        assertSame(original, ActorRuntime.freeze(original));
        // PRIVATE/UNTRUSTED transport must preserve logical identity without
        // exposing the source actor's scalar object reference.
        Method copier = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copier.setAccessible(true);
        Object detached = copier.invoke(null, original);
        assertEquals(original, detached);
        assertNotSame(original, detached);
        assertEquals(OresSymbol.Kind.PROCESS, ((OresSymbol) detached).kind());
    }

    @Test void privateTransportCopiesProcessAndStableSymbolValuesByIdentity()
            throws ReflectiveOperationException {
        Method copier = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copier.setAccessible(true);
        OresSymbol process = OresSymbol.process("ready");
        OresSymbol stable = OresSymbol.stable("550e8400-e29b-41d4-a716-446655440000");
        Object input = List.of(process, stable, process);
        @SuppressWarnings("unchecked")
        List<Object> copied = (List<Object>) copier.invoke(null, input);
        assertEquals(input, copied, "nominal PROCESS/STABLE equality remains stable");
        assertNotSame(input, copied);
        assertNotSame(process, copied.get(0), "PROCESS must be independently owned");
        assertNotSame(stable, copied.get(1), "STABLE must be independently owned");
        assertNotSame(process, copied.get(2));
        assertNotSame(copied.get(0), copied.get(2),
                "each detached transport occurrence gets its own Symbol");
        assertEquals(OresSymbol.Kind.PROCESS, ((OresSymbol) copied.get(0)).kind());
        assertEquals(OresSymbol.Kind.STABLE, ((OresSymbol) copied.get(1)).kind());
    }

    @Test void privateTransportCopiesSymbolKeysAndNestedArrayValues()
            throws ReflectiveOperationException {
        Method copier = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copier.setAccessible(true);
        OresSymbol key = OresSymbol.process("ready");
        OresSymbol value = OresSymbol.process("error");
        var input = new LinkedHashMap<Object, Object>();
        input.put(key, new Object[]{value, key});
        @SuppressWarnings("unchecked")
        Map<Object, Object> copied = (Map<Object, Object>) copier.invoke(null, input);
        OresSymbol copiedKey = (OresSymbol) copied.keySet().iterator().next();
        assertEquals(key, copiedKey);
        assertNotSame(key, copiedKey);
        @SuppressWarnings("unchecked")
        List<Object> copiedArray = (List<Object>) copied.get(key);
        assertEquals(2, copiedArray.size());
        assertEquals(value, copiedArray.get(0));
        assertNotSame(value, copiedArray.get(0));
        assertNotSame(key, copiedArray.get(1));
        assertNotSame(copiedKey, copiedArray.get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> copied.put("injected", "value"));
    }

    @Test void oversizedPrivateAndSharedArrayCopyRejectBeforeSnapshotAllocation()
            throws ReflectiveOperationException {
        Method copier = ActorRuntime.class.getDeclaredMethod("isolateCopy", Object.class);
        copier.setAccessible(true);
        Object[] beyondBudget = new Object[100_001];
        var privateFailure = assertThrows(InvocationTargetException.class,
                () -> copier.invoke(null, (Object) beyondBudget));
        assertInstanceOf(IllegalArgumentException.class, privateFailure.getCause());
        assertTrue(privateFailure.getCause().getMessage().contains("maximum node count"));
        // freeze has a separate preflight that must also fail closed on
        // oversized input before any immutable array snapshot is retained.
        var sharedFailure = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(beyondBudget));
        assertTrue(sharedFailure.getMessage().contains("maximum node count"));
    }

    @Test void mapStillPreservesStringAndSymbolNamespaces() {
        Map<Object, Object> distinct = new LinkedHashMap<>();
        distinct.put("ready", 1L);
        distinct.put(OresSymbol.process("ready"), 2L);
        @SuppressWarnings("unchecked")
        Map<Object, Object> frozen = (Map<Object, Object>) ActorRuntime.freeze(distinct);
        assertEquals(2, frozen.size());
        assertEquals(1L, frozen.get("ready"));
        assertEquals(2L, frozen.get(OresSymbol.process("ready")));
    }

    @Test void lateExpandingListIsBoundedDuringActualDetachment() {
        // freeze() walks the input once for graph admission and once for
        // data-only admission. The hostile third iteration expands while
        // detachment executes, past the value checked by both preflights.
        List<Object> hostile = new java.util.AbstractList<>() {
            int walks;
            @Override public Object get(int index) { return 1L; }
            @Override public int size() { return 1; }
            @Override public java.util.Iterator<Object> iterator() {
                final int actual = ++walks <= 2 ? 1 : 100_001;
                return new java.util.Iterator<>() {
                    int emitted;
                    @Override public boolean hasNext() { return emitted < actual; }
                    @Override public Object next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        emitted++;
                        return 1L;
                    }
                };
            }
        };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ActorRuntime.freeze(hostile));
        assertTrue(failure.getMessage().contains("maximum node count"));
    }

    @Test void copiedGraphCannotExceedWholeGraphNodeBudget() {
        List<Object> oversized = List.of(
                Collections.nCopies(60_000, 1L),
                Collections.nCopies(60_000, 2L));
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(oversized));
    }
}
