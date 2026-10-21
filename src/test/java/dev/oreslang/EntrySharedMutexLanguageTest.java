package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.runtime.OresEntry;
import dev.oreslang.runtime.OresTuple;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class EntrySharedMutexLanguageTest {
    @Test
    void sharedMutexEntryAdmissionMatchesStaticSharedSafeProof() throws Exception {
        String code = """
                pub fnc main(): void {
                  const lock = SharedMutex.new(Entry.of(20, 22));
                  const guard = lock.lock();
                  stdio.println(guard.key + guard.value);
                  guard.release();
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, code, "entry-shared-mutex.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals(
                "42",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
    @Test
    void sharedMutexRuntimeAdmissionRejectsCyclesButAllowsRepeatedAliases() throws Exception {
        Method runtimeSharedSafe = findNestedClass(OresEvalRootNode.class, "MutexFactory")
                .getDeclaredMethod("runtimeSharedSafe", Object.class, Set.class);
        runtimeSharedSafe.setAccessible(true);

        ArrayList<Object> cycle = new ArrayList<>();
        OresEntry cyclicEntry = new OresEntry("cycle", cycle);
        cycle.add(cyclicEntry);
        assertEquals(
                false,
                runtimeSharedSafe.invoke(
                        null,
                        cyclicEntry,
                        Collections.newSetFromMap(new IdentityHashMap<>())));

        ArrayList<Object> aliased = new ArrayList<>(List.of("safe"));
        OresTuple dag = new OresTuple(List.of(aliased, aliased));
        assertEquals(
                true,
                runtimeSharedSafe.invoke(
                        null,
                        dag,
                        Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    private static Class<?> findNestedClass(Class<?> root, String simpleName) {
        for (Class<?> nested : root.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) return nested;
            Class<?> match = findNestedClass(nested, simpleName);
            if (match != null) return match;
        }
        return null;
    }

}
