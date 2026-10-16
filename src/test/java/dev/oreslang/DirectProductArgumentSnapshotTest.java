package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.FixedProductCallPlan;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * White-box regression for the immutable direct-call staging boundary.
 * Java null payloads can arise at host/guest boundaries and must not make
 * a completed one-shot argument evaluation fail during list snapshotting.
 */
final class DirectProductArgumentSnapshotTest {
    @Test
    void directProductSnapshotPreservesNullAndIsolationThroughAbiReordering() throws Exception {
        Class<?> directClass = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Evaluator$DirectArguments");
        Constructor<?> constructor = directClass.getDeclaredConstructor(List.class, List.class);
        constructor.setAccessible(true);

        ArrayList<Object> values = new ArrayList<>(Arrays.asList(null, 23L));
        List<FixedProductCallPlan.InputSlot> slots = List.of(
                FixedProductCallPlan.InputSlot.named("b"),
                FixedProductCallPlan.InputSlot.named("a"));
        Object direct = constructor.newInstance(values, slots);
        values.set(0, 999L);
        values.set(1, 999L);

        Method accessor = directClass.getDeclaredMethod("values");
        accessor.setAccessible(true);
        List<?> snapshot = (List<?>) accessor.invoke(direct);
        assertEquals(Arrays.asList(null, 23L), snapshot);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        Ast.FunctionDecl selected = Parser.parse("""
                fnc encode(int a, int b): int { return a; }
                """).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst().orElseThrow();
        Method abiMethod = directClass.getDeclaredMethod("inAbiOrder", Ast.FunctionDecl.class);
        abiMethod.setAccessible(true);
        List<?> abi = (List<?>) abiMethod.invoke(direct, selected);
        assertEquals(Arrays.asList(23L, null), abi);
        assertThrows(UnsupportedOperationException.class, abi::clear);
    }
}
