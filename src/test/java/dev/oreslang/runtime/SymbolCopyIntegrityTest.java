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

class SymbolCopyIntegrityTest {
    @Test void frozenMapRejectsDistinctHostKeysWithEqualLogicalSymbolIdentity() {
        Map<Object, Object> adversarial = new IdentityHashMap<>();
        adversarial.put(OresSymbol.process("café"), 1L);
        adversarial.put(OresSymbol.process("cafe\u0301"), 2L);
        assertEquals(2, adversarial.size());
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(List.of(adversarial)));
        assertTrue(failure.getMessage().contains("duplicate logical key"));
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
        assertTrue(failure.getCause().getMessage().contains("duplicate logical key"));
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

    @Test void copiedGraphCannotExceedWholeGraphNodeBudget() {
        List<Object> oversized = List.of(
                Collections.nCopies(60_000, 1L),
                Collections.nCopies(60_000, 2L));
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(oversized));
    }
}
