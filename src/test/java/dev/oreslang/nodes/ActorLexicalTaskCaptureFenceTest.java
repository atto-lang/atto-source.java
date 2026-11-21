package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrow mutation tests for private Evaluator.Env Task-capture admission.
 *
 * These use reflection only to emulate a compiler-created, actor-owned
 * scalar slot and to test the exact private fail-closed admission path.
 * They do NOT claim a compiled .atto Task capture or native stack proof.
 */
final class ActorLexicalTaskCaptureFenceTest {
    private record Fixture(Object env, Method preflight, Method materialize,
                           Field owned, Object slot) { }

    private static Fixture lexicalFixture() throws Exception {
        Class<?> envType = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Env");
        Constructor<?> constructor = envType.getDeclaredConstructor(envType);
        constructor.setAccessible(true);
        Object env = constructor.newInstance(new Object[] { null });
        Method define = envType.getDeclaredMethod(
                "define", String.class, Object.class, Ast.BindingKind.class);
        define.setAccessible(true);
        define.invoke(env, "counter", 17L, Ast.BindingKind.LET);

        Field slots = envType.getDeclaredField("slots");
        slots.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> bindings = (Map<String, Object>) slots.get(env);
        Object slot = bindings.get("counter");
        assertNotNull(slot);

        Field owned = slot.getClass().getDeclaredField("actorScalarBound");
        owned.setAccessible(true);
        Method preflight = envType.getDeclaredMethod("preflightTaskCaptures", Map.class);
        preflight.setAccessible(true);
        Method materialize = envType.getDeclaredMethod(
                "detachedTaskSnapshotUnchecked", Map.class, ArrayList.class);
        materialize.setAccessible(true);
        return new Fixture(env, preflight, materialize, owned, slot);
    }

    @Test
    void movingAnActorOwnedScalarCannotCaptureItsStaleJvmShadow() throws Exception {
        Fixture fixture = lexicalFixture();
        fixture.owned().setBoolean(fixture.slot(), true);
        var rejected = assertThrows(InvocationTargetException.class,
                () -> fixture.preflight().invoke(fixture.env(),
                        Map.of("counter", Ast.TaskCaptureTransfer.MOVE)));
        assertInstanceOf(SecurityException.class, rejected.getCause());
        assertTrue(rejected.getCause().getMessage().contains("actor-owned lexical scalar"));
        Field value = fixture.slot().getClass().getDeclaredField("value");
        value.setAccessible(true);
        assertEquals(17L, value.get(fixture.slot()),
                "a denied MOVE must not poison or consume the parent binding");
    }

    @Test
    void copyingAnActorOwnedScalarAlsoCannotBypassFrameGenerationFence()
            throws Exception {
        Fixture fixture = lexicalFixture();
        fixture.owned().setBoolean(fixture.slot(), true);
        var rejected = assertThrows(InvocationTargetException.class,
                () -> fixture.preflight().invoke(fixture.env(),
                        Map.of("counter", Ast.TaskCaptureTransfer.COPY)));
        assertInstanceOf(SecurityException.class, rejected.getCause());
        var bypass = assertThrows(InvocationTargetException.class,
                () -> fixture.materialize().invoke(fixture.env(),
                        Map.of("counter", Ast.TaskCaptureTransfer.COPY),
                        new ArrayList<>()));
        assertInstanceOf(SecurityException.class, bypass.getCause(),
                "unchecked materialization must have its own defense in depth");
    }

    @Test
    void OrdinaryUnmanagedScalarCopyRemainsAdmissible() throws Exception {
        Fixture fixture = lexicalFixture();
        assertFalse(fixture.owned().getBoolean(fixture.slot()));
        LinkedHashMap<String, Ast.TaskCaptureTransfer> policy = new LinkedHashMap<>();
        policy.put("counter", Ast.TaskCaptureTransfer.COPY);
        assertDoesNotThrow(() -> fixture.preflight().invoke(fixture.env(), policy));
        Object detached = fixture.materialize().invoke(
                fixture.env(), policy, new ArrayList<>());
        assertNotNull(detached);
        Field slots = detached.getClass().getDeclaredField("slots");
        slots.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> entries = (Map<String, Object>) slots.get(detached);
        assertTrue(entries.containsKey("counter"));
    }
}
