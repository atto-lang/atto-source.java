package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Public foreign layouts may inform field arithmetic and ownership only after
 * the source graph resolves and validates an explicit class import.
 */
final class LinkedPublicClassFieldTypesTest {
    private static Map<String, String> sources() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/options.ores", """
                pub define class ImportedOptions as
                  pub val int max_input_bytes;
                  pub val List<int> items;
                end
                """);
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';

                pub define class Decoder as
                  pub val ImportedOptions options;
                  pub val List<int> buffer;

                  pub remaining(): int {
                    return self.options.max_input_bytes - self.buffer.length;
                  }
                end
                """);
        return files;
    }

    @Test
    void publicImportedFieldsResolveAsConcreteNumericTypes() {
        var compiler = new IncrementalCompiler();
        var first = assertDoesNotThrow(() -> compiler.compile(sources()));
        assertEquals(2, first.units().size());
        assertEquals(2, first.rebuiltUnits().size());

        var second = compiler.compile(sources());
        assertTrue(second.rebuiltUnits().isEmpty());
        assertEquals(2, second.reusedUnits().size());
    }

    @Test
    void privateClassCannotSupplyAnImportedLayout() {
        var files = sources();
        files.put("src/options.ores", files.get("src/options.ores")
                .replace("pub define class ImportedOptions",
                        "define class ImportedOptions"));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
    }

    @Test
    void wrongFieldTypeCannotBeTreatedAsNumeric() {
        var files = sources();
        files.put("src/options.ores", files.get("src/options.ores")
                .replace("pub val int max_input_bytes",
                        "pub val bool max_input_bytes"));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
    }

    @Test
    void importedAbiChangesRevalidateDependents() {
        var compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(sources()));
        var files = sources();
        files.put("src/options.ores", files.get("src/options.ores")
                .replace("pub val int max_input_bytes",
                        "pub val bool max_input_bytes"));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(files));
        assertEquals(2, compiler.compile(sources()).reusedUnits().size());
    }

    @Test
    void callableFieldAliasesResolveOnlyInsideDefiningModule() {
        var files = new LinkedHashMap<>(sources());
        files.put("src/options.ores", """
                type InternalCallback = () => int;
                pub define class ImportedOptions as
                  pub val int max_input_bytes;
                  pub val List<int> items;
                  pub val InternalCallback callback;
                end
                """);
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';
                fnc answer(): int { return 42; }
                pub fnc make(): ImportedOptions {
                  return new ImportedOptions(10, new List<int>(), answer);
                }
                """);
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(files));
    }

    @Test
    void unimportedPrivateAliasCannotBeNamedInConsumer() {
        var files = new LinkedHashMap<>(sources());
        files.put("src/options.ores", """
                type InternalCallback = () => int;
                pub define class ImportedOptions as
                  pub val int max_input_bytes;
                  pub val List<int> items;
                  pub val InternalCallback callback;
                end
                """);
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';
                pub fnc bad(InternalCallback callback): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
    }

    @Test
    void importedReadOnlyFieldCannotGainMutationThroughProjection() {
        var files = sources();
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';
                pub fnc bad(ImportedOptions options): void {
                  options.items.add(42);
                  return;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
    }
}
