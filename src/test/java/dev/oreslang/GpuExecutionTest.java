package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GpuExecutionTest {
    @Test
    void parsesNamedGpuFunctionAndContextualParallelBatch() {
        Ast.Program named = Parser.parse("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    return a + b;
                  }
                end
                """);

        Ast.FunctionDecl add = (Ast.FunctionDecl) named.modules().getFirst().declarations().getFirst();
        assertTrue(Ast.hasGpuPlacement(add.annotations()));

        Ast.Program batched = Parser.parse("""
                define module app
                  fnc main() => void {
                    const parallel = 7;
                    const funcs = gpu parallel List.of(
                      || -> { return; },
                      || -> { return; },
                      || -> { return; }
                    );
                    return;
                  }
                end
                """);

        Ast.FunctionDecl main = (Ast.FunctionDecl) batched.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = (Ast.BindingStmt) main.body().get(1);
        Ast.GpuExpr gpu = assertInstanceOf(Ast.GpuExpr.class, binding.initializer());
        assertEquals(Ast.GpuMode.PARALLEL, gpu.mode());
        Ast.CallExpr listOf = assertInstanceOf(Ast.CallExpr.class, gpu.expression());
        assertEquals(3, listOf.arguments().size());
        assertTrue(listOf.arguments().stream().allMatch(Ast.LambdaExpr.class::isInstance));
    }

    @Test
    void admitsAConservativeGpuFunction() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    let i32 sum = a + b;
                    return sum;
                  }
                end
                """));
    }

    @Test
    void rejectsCpuCallsFromGpuCode() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module math
                          fnc cpu(i32 x) => i32 {
                            return x + 1;
                          }

                          gpu fnc kernel(i32 x) => i32 {
                            return cpu(x);
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("only statically known gpu functions"));
    }

    @Test
    void rejectsHostCapturesInGpuBatchLambdas() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const i32 hostValue = 7;
                            const funcs = gpu parallel [
                              || -> { return hostValue; },
                              || -> { return; }
                            ];
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("cannot capture or read host/global value"));
    }

    @Test
    void rejectsDynamicMembersOfGpuParallelBatches() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const f = || -> { return; };
                            const funcs = gpu parallel [f];
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("only direct lambda literals"));
    }

    @Test
    void rejectsGpuAsyncAndMisplacedGpuModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  gpu async fnc bad() => void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  gpu define class Bad
                  end
                end
                """));
    }


    @Test
    void gpuLambdaParametersRequireExplicitDeviceAbiTypes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define module app
                          fnc main() => void {
                            const work = gpu |x| -> {
                              return;
                            };
                            return;
                          }
                        end
                        """));

        assertTrue(failure.getMessage().contains("explicit GPU-transferable type"));
    }

    @Test
    void gpuExecutionNeverFallsBackToCpuInterpreter() throws Exception {
        String program = """
                define module app
                  gpu fnc kernel(i32 x) => i32 {
                    return x + 1;
                  }

                  pub fnc main() => void {
                    kernel(41);
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "gpu-no-fallback.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("no GPU lowering/backend")
                    || failure.getMessage().contains("never falls back to CPU"));
        }
    }
}
