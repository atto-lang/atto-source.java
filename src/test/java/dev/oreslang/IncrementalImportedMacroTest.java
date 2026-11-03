package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.MacroSyntaxProvenance;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-unit, source-verified M1 macro import acceptance and rejection.
 * These tests require the real Java compiler; source-free canaries do not
 * substitute for their JUnit execution.
 */
final class IncrementalImportedMacroTest {
    private static final String PUBLIC_LITERAL = """
            define pub macro literal(Const<int> value) -> Syntax<Expr> {
                return quote(value);
            }
            """;

    private static final String CONSUMER = """
            import * as lib from "./producer.atto";

            pub fnc answer() -> int {
                return lib.literal!(const 40 + 2);
            }
            """;

    private static Map<String, String> units(String producer, String consumer) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        sources.put("producer.atto", producer);
        sources.put("consumer.atto", consumer);
        return sources;
    }

    private static Ast.Expr returnValue(IncrementalCompiler.BuildResult built) {
        Ast.Program checked = built.units().get("consumer.atto").program();
        Ast.FunctionDecl fn = checked.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(candidate -> candidate.name().equals("answer"))
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        return returned.value();
    }

    @Test
    void importedConstMacroExpandsAndReentersGuestTypeChecking() {
        IncrementalCompiler.BuildResult built =
                new IncrementalCompiler().compile(units(PUBLIC_LITERAL, CONSUMER));
        Ast.Expr expr = returnValue(built);
        Ast.LiteralExpr literal = assertInstanceOf(Ast.LiteralExpr.class, expr);
        assertEquals(42L, literal.value());
        assertTrue(built.rebuilt("producer.atto"));
        assertTrue(built.rebuilt("consumer.atto"));
        List<MacroSyntaxProvenance.Origin> origins = MacroSyntaxProvenance.lookup(expr);
        assertFalse(origins.isEmpty(), "expanded AST must retain compiler-only origin");
        assertEquals("producer.atto::__root__", origins.getLast().hygiene().macroOwnerModule());
        assertEquals("consumer.atto", origins.getLast().hygiene().sourceUnitId());
    }

    @Test
    void extensionlessImportPrefersAttoOverLegacyOres() {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>(units(
                PUBLIC_LITERAL,
                CONSUMER.replace("./producer.atto", "./producer")));
        sources.put("producer.ores", """
                define pub macro different(Const<int> value) -> Syntax<Expr> {
                    return quote(value);
                }
                """);
        IncrementalCompiler.BuildResult built = new IncrementalCompiler().compile(sources);
        assertEquals(42L, assertInstanceOf(Ast.LiteralExpr.class, returnValue(built)).value());
    }

    @Test
    void importedIdentityPreservesCallerExpressionWithoutSourceReparse() {
        String producer = """
                define pub macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                    return value;
                }
                """;
        String consumer = CONSUMER.replace("literal!(const 40 + 2)",
                "identity!(syntax expr 42)");
        IncrementalCompiler.BuildResult built =
                new IncrementalCompiler().compile(units(producer, consumer));
        assertEquals(42L, assertInstanceOf(Ast.LiteralExpr.class, returnValue(built)).value());
        assertFalse(MacroSyntaxProvenance.lookup(returnValue(built)).isEmpty());
    }

    @Test
    void privateMacroIsNeverImportableThroughQualifiedAlias() {
        String producer = PUBLIC_LITERAL.replace("define pub macro", "define macro");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(producer, CONSUMER)));
        assertTrue(failure.getMessage().contains("unresolved compile-time macro"),
                failure::toString);
    }

    @Test
    void qualificationWithoutImportDoesNotGrantMacroAuthority() {
        String consumer = CONSUMER.replace(
                "import * as lib from \"./producer.atto\";", "");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(PUBLIC_LITERAL, consumer)));
        assertTrue(failure.getMessage().contains("unresolved compile-time macro"),
                failure::toString);
    }

    @Test
    void importedIdentifierGeneratorFailsClosedUntilCrossUnitHygieneIsProven() {
        String producer = """
                define pub macro create(Identifier value) -> Syntax<Expr> {
                    return hygienic(value);
                }
                """;
        String consumer = CONSUMER.replace("literal!(const 40 + 2)", "create!(ident hidden)");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(producer, consumer)));
        assertTrue(failure.getMessage().contains("outside M1"),
                failure::toString);
    }

    @Test
    void ambiguousPublicMacrosFromTwoSourceModulesAreRejected() {
        String producer = """
                define module Alpha as
                    define pub macro literal(Const<int> value) -> Syntax<Expr> {
                        return quote(value);
                    }
                end
                define module Beta as
                    define pub macro literal(Const<int> value) -> Syntax<Expr> {
                        return quote(value);
                    }
                end
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(producer, CONSUMER)));
        assertTrue(failure.getMessage().contains("ambiguous imported compile-time macro"),
                failure::toString);
    }

    @Test
    void publicMacroBodyChangesInvalidateDependentAttoUnit() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(units(PUBLIC_LITERAL, CONSUMER)));
        String changed = PUBLIC_LITERAL.replace("value", "item");
        IncrementalCompiler.BuildResult next =
                assertDoesNotThrow(() -> compiler.compile(units(changed, CONSUMER)));
        assertTrue(next.rebuilt("producer.atto"));
        assertTrue(next.rebuilt("consumer.atto"),
                "public macro implementation changes must invalidate its .atto dependents");
    }

    @Test
    void duplicateWildcardAliasesCannotMergeSourceAuthorities() {
        String consumer = CONSUMER.replace(
                "import * as lib from \"./producer.atto\";",
                "import * as lib from \"./producer.atto\";\n"
                        + "import * as lib from \"./producer.atto\";");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(PUBLIC_LITERAL, consumer)));
        assertTrue(failure.getMessage().contains("duplicate compile-time macro import alias"),
                failure::toString);
    }

    @Test
    void wildcardAliasCannotShadowLocalSourceModule() {
        String consumer = """
                import * as lib from "./producer.atto";
                define module lib as
                    pub fnc placeholder() -> int {
                        return 1;
                    }
                end
                pub fnc answer() -> int {
                    return lib.literal!(const 42);
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(units(PUBLIC_LITERAL, consumer)));
        assertTrue(failure.getMessage().contains("collides with a local source module"),
                failure::toString);
    }

    @Test
    void unusedUnsupportedImportedMacroDoesNotBlockSafeScalarImport() {
        String producer = PUBLIC_LITERAL + """
                define pub macro generate(Identifier value) -> Syntax<Expr> {
                    return hygienic(value);
                }
                """;
        IncrementalCompiler.BuildResult built =
                assertDoesNotThrow(() -> new IncrementalCompiler()
                        .compile(units(producer, CONSUMER)));
        assertEquals(42L, assertInstanceOf(Ast.LiteralExpr.class, returnValue(built)).value(),
                "uninvoked imported identifier generators must not create runtime authority");
    }

    @Test
    void resolvedAbsolutePathStillCannotAuthorizeMacroExport() {
        String absPath = "/private/checkout/producer.atto";
        String consumer = CONSUMER.replace("./producer.atto", absPath);
        Map<String, Map<String, String>> forgedResolutions =
                Map.of("consumer.atto", Map.of(absPath, "producer.atto"));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(
                        units(PUBLIC_LITERAL, consumer), forgedResolutions));
        assertTrue(denied.getMessage().contains("absolute host path"),
                denied::toString);
    }
}
