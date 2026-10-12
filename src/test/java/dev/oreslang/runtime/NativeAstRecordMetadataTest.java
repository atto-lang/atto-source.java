package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guard the native-image reflection manifest against AST grammar evolution.
 * Native source typechecking walks record components reflectively, so a new
 * Ast record without matching native reachability silently passes JVM tests
 * but fails only when an AOT/hybrid guest program is launched.
 */
final class NativeAstRecordMetadataTest {
    @Test
    void allAndOnlyAstRecordTypesHaveNativeAccessorMetadata() throws Exception {
        String resource = "/META-INF/native-image/dev.oreslang/oreslang-source/reflect-config.json";
        String manifest;
        try (InputStream stream = Ast.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "native image AST reflection metadata must ship with runtime");
            manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> declared = Arrays.stream(Ast.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .map(Class::getName)
                .collect(Collectors.toSet());
        assertFalse(declared.isEmpty());

        Set<String> registered = new HashSet<>();
        for (String line : manifest.split("\\R")) {
            String prefix = "\"name\": \"";
            int index = line.indexOf(prefix);
            if (index < 0) continue;
            int start = index + prefix.length();
            int end = line.indexOf('"', start);
            assertTrue(end > start, "malformed native reflection metadata line: " + line);
            String recordName = line.substring(start, end);
            assertTrue(registered.add(recordName), "duplicate AST record metadata: " + recordName);
        }
        assertEquals(declared, registered,
                "native record-component reflection must track the exact compiled Ast records");
        assertEquals(declared.size(), count(manifest, "\"allDeclaredMethods\": true"),
                "every reflected record requires its accessor methods");
        assertEquals(declared.size(), count(manifest, "\"queryAllDeclaredMethods\": true"));

        for (Class<?> recordType : Ast.class.getDeclaredClasses()) {
            if (!recordType.isRecord()) continue;
            assertNotNull(recordType.getRecordComponents());
            for (var component : recordType.getRecordComponents()) {
                assertNotNull(recordType.getDeclaredMethod(component.getName()),
                        "missing accessor on " + recordType.getName());
            }
        }
    }

    private static int count(String text, String fragment) {
        int occurrences = 0;
        for (int cursor = 0; (cursor = text.indexOf(fragment, cursor)) >= 0;
             cursor += fragment.length()) {
            occurrences++;
        }
        return occurrences;
    }
}
