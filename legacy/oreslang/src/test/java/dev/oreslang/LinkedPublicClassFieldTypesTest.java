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

    @Test
    void linkedBuildPreservesShortInferredAliasAndExactSelectorOwnershipPrecision() {
        var files = sources();
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';

                pub fnc read(ImportedOptions options): int {
                  const Row row = infer record{id: 7, options: options};
                  const key = "id";
                  return row[key];
                }
                """);
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(files));
    }

    @Test
    void addingFirstPrivateFieldInvalidatesDynamicIndexImporters() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/options.ores", """
                pub define class ImportedOptions as
                  pub let int shown;

                  pub constructor(int shown) {
                    self.shown = shown;
                  }
                end
                """);
        files.put("src/decoder.ores", """
                import class ImportedOptions from './options';

                pub fnc read(ImportedOptions options, string key): int {
                  return options[key];
                }
                """);

        var compiler = new IncrementalCompiler();
        var first = assertDoesNotThrow(() -> compiler.compile(files));
        assertEquals(2, first.rebuiltUnits().size());

        Map<String, String> withPrivateField = new LinkedHashMap<>(files);
        withPrivateField.put("src/options.ores", """
                pub define class ImportedOptions as
                  pub let int shown;
                  private let int hidden;

                  pub constructor(int shown) {
                    self.shown = shown;
                    self.hidden = 0;
                  }
                end
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(withPrivateField));
        assertTrue(
                failure.getMessage().contains("private field 'ImportedOptions.hidden'"),
                () -> "unexpected linked dynamic-index failure: " + failure.getMessage());
    }

    @Test
    void linkedInheritedLayoutsRemainFailClosedWithoutParentClosureMetadata() {
        var files = new LinkedHashMap<String, String>();
        files.put("src/model.ores", """
                define class Parent<T> as
                  pub val T inherited;
                end

                pub define class Child<U> extends Parent<U> as
                  pub val U own;
                end
                """);
        files.put("src/use.ores", """
                import class Child from './model';

                pub fnc read(Child<int> value): int {
                  return value["inherited"];
                }
                """);

        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
    }

}
