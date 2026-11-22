package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Typed constant import contract, parser + export-kind admission only.
 * This is deliberately NOT guest execution or runtime env initialization.
 */
final class ImportConstTargetAdmissionTest {

    @Test
    void typedSelectorsPreserveSourceNamesAndIndependentLocalAliases() {
        Ast.ImportDecl select = Parser.parse("""
                import const status_valid, status_extra as more from "std.http";
                pub fnc main(): void { return; }
                """).imports().getFirst();

        assertEquals(Ast.ImportKind.CONST, select.kind());
        assertEquals(List.of("status_valid", "status_extra"), select.names());
        assertEquals(Map.of("status_extra", "more"), select.constAliases());
        assertEquals(List.of("status_valid", "more"), ImportRules.exposedBindings(select));

        Ast.ImportDecl pkg = Parser.parse("""
                import const status_valid as ok from pkg "std.http";
                pub fnc main(): void { return; }
                """).imports().getFirst();
        assertEquals("std.http", pkg.path());
        assertEquals(List.of("ok"), ImportRules.exposedBindings(pkg));
    }

    @Test
    void wildcardDuplicateAliasesAndJavaHostConstImportsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                import const * as http from "std.http";
                pub fnc main(): void { return; }
                """));

        Ast.ImportDecl duplicates = Parser.parse("""
                import const a as same, b as same from "./config.ores";
                pub fnc main(): void { return; }
                """).imports().getFirst();
        assertThrows(IllegalArgumentException.class, () -> ImportRules.validate(duplicates));

        Ast.ImportDecl javaHost = Parser.parse("""
                import const status_valid from "java:java.lang.System";
                pub fnc main(): void { return; }
                """).imports().getFirst();
        assertThrows(IllegalArgumentException.class, () -> ImportRules.validate(javaHost));
    }

    @Test
    void linkedPublicConstantIsKindCheckedButNotSilentlyExecuted() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("config.ores", "define pub const int status_valid = 200;");
        sources.put("main.ores", """
                import const status_valid from "./config.ores";
                pub fnc main(): void { return; }
                """);
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(unsupported.getMessage().contains("E_UNSUPPORTED"), unsupported.getMessage());

        sources.put("config.ores", "define const int status_valid = 200;");
        IllegalArgumentException hidden = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(hidden.getMessage().contains("does not match an exported declaration"),
                hidden.getMessage());

        sources.put("config.ores", "pub fnc status_valid(): int { return 200; }");
        IllegalArgumentException wrongKind = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(wrongKind.getMessage().contains("does not match an exported declaration"),
                wrongKind.getMessage());
    }

    @Test
    void runtimeEnvironmentConstMustNotBeFoldedAtCompilationTime() {
        // A runtime environment snapshot is a separate startup capability;
        // the existing Java constexpr folder must not call the host env.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "define pub const String status_valid = std.env.read_env(\"status_valid\");")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "define pub const String status_valid = os.read_env(\"status_valid\");")));
    }
}
