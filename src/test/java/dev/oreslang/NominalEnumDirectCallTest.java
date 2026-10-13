package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Executable direct-call overloads: only prebound static nominal enum IDs. */
final class NominalEnumDirectCallTest {
    private static final String ENUMS = """
            define enum Color as
                Red
                Blue
            end
            define enum Mode as
                Fast: int
                Slow: int
            end
            """;

    private static String run(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "nominal-enum-overloads.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void directEnumFncsSelectOnlyByStaticallyKnownOwner() throws Exception {
        String output = run(ENUMS + """
                fnc choose(Color c): int { return 11; }
                fnc choose(Mode m): int { return 29; }
                fnc choose(int value, int n): int { return value + n; }

                pub routine main(): void {
                    stdio.stdout.write(choose(Color.Red()));
                    stdio.stdout.write(":");
                    stdio.stdout.write(choose(Mode.Fast(3)));
                    stdio.stdout.write(":");
                    stdio.stdout.write(choose(4, 5));
                }
                """);
        assertEquals("11:29:9", output);
    }

    @Test
    void namespaceQualifiedCallsUseExactSameCompilerBinding() throws Exception {
        String output = run(ENUMS + """
                define module dispatch as
                    pub fnc pick(Color c): int { return 7; }
                    pub fnc pick(Mode m): int { return 8; }
                end
                pub routine main(): void {
                    stdio.stdout.write(dispatch.pick(Color.Blue()));
                    stdio.stdout.write(dispatch.pick(Mode.Fast(9)));
                }
                """);
        assertEquals("78", output);
    }

    @Test
    void partiallyKnownTwoPositionEnumFamilyFailsClosed() {
        String family = ENUMS + """
                fnc select(Color left, Color right): int { return 1; }
                fnc select(Mode left, Mode right): int { return 2; }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse(family + """
                        fnc wrong(Color first, Any unknown): void {
                            select(first, unknown);
                        }
                        """)));
    }

    @Test
    void cannotChooseByPrimitiveStructuralVariantOrUnknown() {
        String[] badCalls = {
                "fnc bad(Any unknown): void { choose(unknown); }",
                "fnc bad(Color | Mode unknown): void { choose(unknown); }"
        };
        String family = ENUMS + """
                fnc choose(Color c): int { return 1; }
                fnc choose(Mode m): int { return 2; }
                """;
        for (String bad : badCalls) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(family + bad)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse("""
                        fnc bad(int i): void { return; }
                        fnc bad(string s): void { return; }
                        """)));
    }

    @Test
    void methodAndStaticOverloadsRemainBlockedUntilExactVtableSlotsExist() {
        String methods = ENUMS + """
                define class Box as
                    pub use(Color c): void { return; }
                    pub use(Mode m): void { return; }
                end
                """;
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse(methods)));
    }

    @Test
    void treeShakingMustNotEraseCompilerBoundOverloadIdentities() {
        String family = ENUMS + """
                fnc choose(Color c): int { return 1; }
                fnc choose(Mode m): int { return 2; }
                pub routine main(): void { stdio.stdout.write(choose(Color.Red())); }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                dev.oreslang.compiler.OresCompiler.compileForBuild(
                        family, dev.oreslang.compiler.BuildOptions.executable()));
        assertTrue(error.getMessage().contains("rebinding callable IDs"),
                error.getMessage());
    }

    @Test
    void directCallableValuesOfSameArityNeedExplicitSignatureSpecialization() {
        String source = ENUMS + """
                fnc choose(Color c): int { return 1; }
                fnc choose(Mode m): int { return 2; }
                fnc bad(): void {
                    val cb = choose;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test
    void privateOverloadCannotBeCalledThroughPublicModuleFacade() {
        String source = ENUMS + """
                define module dispatch as
                    pub fnc pick(Color c): int { return 1; }
                    fnc pick(Mode m): int { return 2; }
                end
                fnc forbidden(): int {
                    return dispatch.pick(Mode.Fast(1));
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("private"), failure.getMessage());
    }

    @Test
    void aliasOrUnboundModuleAccessCannotDynamicallyChoosePublicEnumOverload() {
        String source = ENUMS + """
                define module dispatch as
                    pub fnc pick(Color c): int { return 1; }
                    fnc pick(Mode m): int { return 2; }
                end
                pub routine main(): void {
                    val alias = dispatch;
                    stdio.stdout.write(alias.pick(Color.Red()));
                }
                """;
        // The type checker may reject first-class module aliases entirely.
        // If it admits one, an unbound runtime call must still reject
        // instead of selecting the only PUBLIC overload by iteration.
        assertThrows(RuntimeException.class, () -> run(source));
    }
}
