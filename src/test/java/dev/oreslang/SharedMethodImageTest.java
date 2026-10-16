package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.CallableSelector;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression gate: per-instance data must not contain a copy of method
 * definitions or the effective class method dispatch table.
 */
final class SharedMethodImageTest {
    @Test
    void allClassInstancesReuseOneLinkedMethodImage() throws Exception {
        Ast.Program ast = Parser.parse("""
                define class Counter as
                  pub fnc first(): int { return 1; }
                  pub fnc add(int x): int { return x + 1; }
                end
                """);
        Ast.ClassDecl definition = (Ast.ClassDecl) ast.modules().getFirst()
                .declarations().getFirst();

        Class<?> evaluatorClass = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$Evaluator");
        Constructor<?> evaluatorConstructor = Arrays.stream(
                evaluatorClass.getDeclaredConstructors())
                .filter(ctor -> ctor.getParameterCount() == 3)
                .findFirst().orElseThrow();
        evaluatorConstructor.setAccessible(true);
        Object evaluator = evaluatorConstructor.newInstance(ast, null, "shared-code-test");

        Field slotsField = evaluatorClass.getDeclaredField("callableSlots");
        slotsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Ast.ClassDecl, Map<CallableSelector, Ast.MethodDecl>> slots =
                (Map<Ast.ClassDecl, Map<CallableSelector, Ast.MethodDecl>>)
                        slotsField.get(evaluator);
        assertEquals(1, slots.size(), "a class should have exactly one effective dispatch table");
        Map<CallableSelector, Ast.MethodDecl> shared = slots.get(definition);
        assertNotNull(shared);
        assertSame(definition.methods().getFirst(), shared.get(CallableSelector.instance("first", 0)));
        assertSame(definition.methods().get(1), shared.get(CallableSelector.instance("add", 1)));

        Class<?> objectClass = Class.forName(
                "dev.oreslang.nodes.OresEvalRootNode$OresObject");
        Constructor<?> objectConstructor = objectClass.getDeclaredConstructor(
                evaluatorClass, Ast.ClassDecl.class, Map.class);
        objectConstructor.setAccessible(true);
        Field classField = objectClass.getDeclaredField("klass");
        classField.setAccessible(true);
        Field instanceFields = objectClass.getDeclaredField("fields");
        instanceFields.setAccessible(true);

        Set<String> names = Arrays.stream(objectClass.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());
        assertEquals(Set.of("owner", "klass", "fields"), names,
                "an instance must not contain method copies or vtable slots");

        Object first = null;
        for (int i = 0; i < 1024; i++) {
            LinkedHashMap<String, Object> ownData = new LinkedHashMap<>();
            ownData.put("value", i);
            Object instance = objectConstructor.newInstance(evaluator, definition, ownData);
            assertSame(definition, classField.get(instance));
            assertSame(ownData, instanceFields.get(instance));
            assertSame(shared, slots.get(definition));
            if (first == null) first = instance;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> firstFields = (Map<String, Object>) instanceFields.get(first);
        firstFields.put("value", -1);
        assertEquals(-1, firstFields.get("value"));
        assertSame(shared, slots.get(definition));
    }
}
