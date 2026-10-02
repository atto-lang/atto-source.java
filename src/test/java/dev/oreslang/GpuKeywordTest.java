package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.gpu.GpuRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class GpuKeywordTest {
    @AfterEach
    void clearGpuBackend() {
        GpuRuntime.clearBackend();
    }

    @Test
    void lexerAndParserPreserveGpuModifier() {
        var tokens = new Lexer("pub gpu fnc kernel(int x) => int { return x; }").scan();
        assertTrue(tokens.stream().anyMatch(token -> token.type() == Token.Type.GPU));

        Ast.Program program = Parser.parse("pub gpu fnc kernel(int x) => int { return x; }");
        Ast.FunctionDecl kernel = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.CallableKind.FNC, kernel.kind());
        assertTrue(kernel.gpu());
        assertEquals(Ast.Visibility.PUBLIC, kernel.visibility());
    }

    @Test
    void gpuModifierIsRestrictedToNamedTopLevelOrModuleCallables() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                gpu define class Bad
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module m
                  define class Bad
                    gpu work() => int {
                      return 1;
                    }
                  end
                end
                """));
    }

    @Test
    void gpuCallablesMayComposeGpuCallables() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                gpu fnc square(int x) => int {
                  return x * x;
                }

                gpu routine kernel(int x) => int {
                  return square(x);
                }
                """)));
    }

    @Test
    void gpuCallableCannotCallCpuCallable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc cpu_helper(int x) => int {
                          return x + 1;
                        }

                        gpu fnc kernel(int x) => int {
                          return cpu_helper(x);
                        }
                        """)));
        assertTrue(failure.getMessage().contains("cannot call CPU callable"));
    }

    @Test
    void gpuCallableRejectsHostEffectsAndUnsupportedControlEffects() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                gpu routine kernel() => void {
                  stdio.println("no");
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                gpu routine kernel() => void {
                  try {
                    return;
                  } catch (err) {
                    return;
                  }
                }
                """)));
    }

    @Test
    void cpuToGpuCallsUseInstalledBackendInsteadOfEvaluatingBodyOnCpu() throws Exception {
        GpuRuntime.installBackend(new GpuRuntime.Backend() {
            @Override public String name() { return "test-gpu"; }

            @Override
            public Object invoke(GpuRuntime.Invocation invocation) {
                assertEquals("__root__.add", invocation.callable());
                assertEquals("fnc", invocation.callableKind());
                assertEquals(2L, ((Number) invocation.arguments().get(0)).longValue());
                assertEquals(3L, ((Number) invocation.arguments().get(1)).longValue());
                return 41L;
            }
        });

        String output = run("""
                gpu fnc add(int a, int b) => int {
                  return 999;
                }

                pub routine main() => void {
                  stdio.stdout.write(add(2, 3));
                }
                """);

        assertEquals("41", output);
    }

    @Test
    void gpuNeverSilentlyFallsBackToCpu() {
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run("""
                pub gpu routine main() => void {
                  return;
                }
                """));

        assertTrue(failure.getMessage().contains("CPU fallback is forbidden"));
    }

    @Test
    void isolateAdmissionRequiresExplicitGpuCapability() {
        String source = """
                gpu fnc kernel(int x) => int {
                  return x;
                }
                """;

        assertThrows(SecurityException.class,
                () -> OresCompiler.validateForIsolate(source, IsolatePolicy.strictFaas()));

        IsolatePolicy gpuPolicy = IsolatePolicy.strictFaas()
                .withCapabilities(IsolatePolicy.Capability.GPU);
        assertDoesNotThrow(() -> OresCompiler.validateForIsolate(source, gpuPolicy));
    }

    @Test
    void gpuEffectParticipatesInExportedAbiDigest() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        var cpu = compiler.compile(Map.of("kernel.ores", """
                pub fnc kernel(int x) => int {
                  return x;
                }
                """));
        String cpuAbi = cpu.units().get("kernel.ores").abiDigest();

        var gpu = compiler.compile(Map.of("kernel.ores", """
                pub gpu fnc kernel(int x) => int {
                  return x;
                }
                """));
        String gpuAbi = gpu.units().get("kernel.ores").abiDigest();

        assertNotEquals(cpuAbi, gpuAbi);
        assertTrue(gpu.rebuilt("kernel.ores"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "gpu-test.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID,
                        "--ores-capabilities=STDOUT,GPU")
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
