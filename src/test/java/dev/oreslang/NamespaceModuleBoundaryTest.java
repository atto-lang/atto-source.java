package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NamespaceModuleBoundaryTest {

    @Test
    void namespaceIsSourceMetadataAndNamedModuleRemainsLinkable() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("provider.ores", """
                namespace source_api;
                define module math as
                  pub fnc add(int value): int { return value + 1; }
                end
                """);
        sources.put("consumer.ores", """
                namespace client;
                import module math from "./provider.ores";
                pub routine main(): void { return; }
                """);

        var build = new IncrementalCompiler().compile(sources);
        assertEquals("source_api", build.units().get("provider.ores").namespace());
        assertEquals("source_api", build.units().get("provider.ores").packageId());
        assertEquals("client", build.units().get("consumer.ores").namespace());
    }

    @Test
    void syntheticFileRootCannotBeSelectedAsAnImportedModule() {
        Map<String, String> sources = Map.of(
                "provider.ores", """
                        namespace source_api;
                        pub fnc publicFunction(): int { return 42; }
                        """,
                "consumer.ores", """
                        import module __root__ from "./provider.ores";
                        pub routine main(): void { return; }
                        """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(failure.getMessage().contains("does not match an exported declaration"),
                failure::getMessage);
    }

    @Test
    void userCannotDefineSyntheticRootModule() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module __root__ as
                          pub fnc x(): int { return 1; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("reserved for file-root declarations"),
                failure::getMessage);
    }

    @Test
    void namespacesAreFlatAndAppearOnlyBeforeImportsAndDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                namespace org.lib;
                pub routine main(): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void { return; }
                namespace later;
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                namespace one;
                namespace two;
                pub routine main(): void { return; }
                """));

        TypeChecker.check(Parser.parse("""
                namespace public_api;
                define module math as
                  pub type Number = int;
                  pub fnc add(int a, int b): int { return a + b; }
                end
                """));
    }
}
