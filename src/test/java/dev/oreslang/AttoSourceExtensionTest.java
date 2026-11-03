package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.DurableExecutionManifest;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The public AttoLang source extension is .atto; legacy .ores remains a bridge. */
final class AttoSourceExtensionTest {
    @TempDir Path temp;

    private static String fixture() throws Exception {
        try (InputStream in = AttoSourceExtensionTest.class.getResourceAsStream(
                "/fixtures/durable/m0-checkout.atto")) {
            assertNotNull(in, "canonical .atto fixture must be packaged in the JUnit classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void attoFileIsClassifiedAsNativeGuestSourceWithoutFallingBackToJava() throws Exception {
        String source = fixture();
        MixedSourceUnit unit = MixedSourceUnit.parse(
                "/guest/m0-checkout.atto", "m0-checkout.atto", source);
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES, unit.primaryLanguage(),
                "Internal ORES enum identity remains compatible during public rename");
        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource());
        assertEquals(source, unit.oresSource());
    }

    @Test
    void attoFixtureParsesAndTypeChecksDurableDeclarationsWithoutExecution() throws Exception {
        Ast.Program parsed = Parser.parse(fixture());
        Ast.Program checked = TypeChecker.checkTypes(parsed);
        List<Ast.Decl> declarations = checked.modules().getFirst().declarations();
        assertInstanceOf(Ast.ActivityDecl.class, declarations.get(0));
        assertInstanceOf(Ast.WorkflowDecl.class, declarations.get(1));
        Ast.WorkflowDecl workflow = (Ast.WorkflowDecl) declarations.get(1);
        assertTrue(workflow.commands().isEmpty(),
                "AttoLang recorded command grammar remains closed in M0");
        DurableExecutionManifest manifest = DurableExecutionManifest.analyze(parsed);
        assertTrue(new String(manifest.canonicalBytes(), StandardCharsets.UTF_8)
                .contains("\"format\":\"atto.durable.manifest.v1\""));
        assertFalse(manifest.sha256().isBlank());
    }

    @Test
    void extensionlessImportPrefersAttoOverLegacyOresFile() throws Exception {
        Path importer = temp.resolve("main.atto");
        Path preferred = temp.resolve("service.atto");
        Files.writeString(preferred, "fnc load(): int { return 1; }");
        Files.writeString(temp.resolve("service.ores"), "fnc load(): int { return 2; }");

        OresProjectConfig project = OresProjectConfig.discover(temp, Map.of());
        assertEquals(preferred.toAbsolutePath().normalize(),
                project.resolveImport(importer, "service").orElseThrow());
        assertEquals(preferred.toAbsolutePath().normalize(),
                project.resolveImport(importer, "./service.atto").orElseThrow());
    }

    @Test
    void legacyImportFallbackWorksOnlyWhenNoAttoFileExists() throws Exception {
        Path importer = temp.resolve("main.atto");
        Path legacy = temp.resolve("service.ores");
        Files.writeString(legacy, "fnc load(): int { return 1; }");
        OresProjectConfig project = OresProjectConfig.discover(temp, Map.of());
        assertEquals(legacy.toAbsolutePath().normalize(),
                project.resolveImport(importer, "service").orElseThrow());

        Path preferred = temp.resolve("service.atto");
        Files.writeString(preferred, "fnc load(): int { return 2; }");
        assertEquals(preferred.toAbsolutePath().normalize(),
                project.resolveImport(importer, "service").orElseThrow());
    }
    @Test
    void incrementalCompilerUsesAttoPackageIdsAndPrefersAttoImports() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        var result = compiler.compile(Map.of(
                "service.atto", "pub fnc calc(): int { return 1; }",
                "service.ores", "pub fnc calc(): int { return 2; }",
                "main.atto", """
                        import fnc {calc} from "./service";
                        pub fnc main(): void { return; }
                        """));
        assertEquals("main", result.units().get("main.atto").packageId());
        assertEquals("service", result.units().get("service.atto").packageId());
        assertEquals(java.util.Set.of("service.atto"), result.units().get("main.atto").dependencies());
    }

}
