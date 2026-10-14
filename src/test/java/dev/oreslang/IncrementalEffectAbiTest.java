package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Changes to exported callable effects change the linker's ABI digest. */
final class IncrementalEffectAbiTest {
    private static final String IMPORT_FN = """
            import fnc value from './lib';
            pub fnc main(): void { return; }
            """;

    private static void assertImporterRebuilds(String before, String after, String importer) {
        IncrementalCompiler compiler = new IncrementalCompiler();
        compiler.compile(Map.of(
                "pkg/lib.ores", before,
                "pkg/main.ores", importer));
        var updated = compiler.compile(Map.of(
                "pkg/lib.ores", after,
                "pkg/main.ores", importer));
        assertTrue(updated.rebuilt("pkg/lib.ores"));
        assertTrue(updated.rebuilt("pkg/main.ores"),
                "an exported callable effect change must invalidate the importer");
    }

    @Test
    void publicFunctionSuspensionChangesInvalidateImporters() {
        assertImporterRebuilds(
                "pub fnc value(): int { return 7; }",
                "pub suspends fnc value(): int { return 7; }",
                IMPORT_FN);
    }

    @Test
    void publicFunctionTrapChangesInvalidateImporters() {
        assertImporterRebuilds(
                "pub fnc value(): int { return 7; }",
                "pub trap fnc value(): int { return 7; }",
                IMPORT_FN);
    }

    @Test
    void publicStaticMethodSuspensionChangesInvalidateClassImporters() {
        assertImporterRebuilds("""
                define pub class Service as
                  pub static fnc get(): int { return 1; }
                end
                """, """
                define pub class Service as
                  pub static suspends fnc get(): int { return 1; }
                end
                """, """
                import class Service from './lib';
                pub fnc main(): void { return; }
                """);
    }

    @Test
    void publicClassAbstractionChangesInvalidateClassImporters() {
        assertImporterRebuilds("""
                define pub class Service as end
                """, """
                define pub abstract class Service as end
                """, """
                import class Service from './lib';
                pub fnc main(): void { return; }
                """);
    }

    @Test
    void sameTypedPrivateAndPublicConstructorFieldReordersInvalidateImporters() {
        assertImporterRebuilds("""
                define pub class Box as
                  private int hidden: 1
                  pub int visible: 2
                end
                """, """
                define pub class Box as
                  pub int visible: 2
                  private int hidden: 1
                end
                """, """
                import class Box from './lib';
                pub fnc main(): void { return; }
                """);
    }

    @Test
    void synthesizedConstructorFieldVisibilityChangesInvalidateImporters() {
        assertImporterRebuilds("""
                define pub class Box as
                  private int first: 1
                  pub int second: 2
                end
                """, """
                define pub class Box as
                  pub int first: 1
                  pub int second: 2
                end
                """, """
                import class Box from './lib';
                pub fnc main(): void { return; }
                """);
    }

    @Test
    void privateFunctionEffectsDoNotChangeAnUnrelatedPublicAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String api = "pub fnc value(): int { return 7; }\n";
        String initial = api + "fnc helper(): int { return 2; }\n";
        String changed = api + "suspends fnc helper(): int { return 2; }\n";
        compiler.compile(Map.of(
                "pkg/lib.ores", initial,
                "pkg/main.ores", IMPORT_FN));
        var updated = compiler.compile(Map.of(
                "pkg/lib.ores", changed,
                "pkg/main.ores", IMPORT_FN));
        assertTrue(updated.rebuilt("pkg/lib.ores"));
        assertTrue(updated.reused("pkg/main.ores"),
                "private callable effects must not widen the exported ABI");
    }
}
