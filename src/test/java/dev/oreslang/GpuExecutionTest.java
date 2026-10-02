package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelCompiler;
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
    void compilerActuallyEmitsOpenClForNamedGpuFunctions() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc add(i32 a, i32 b) => i32 {
                    let i32 sum = a + b;
                    return sum;
                  }
                end
                """);

        GpuKernelCompiler.GpuProgram gpu = compilation.gpuProgram();
        assertTrue(gpu.hasKernels());
        assertEquals(GpuKernelCompiler.BACKEND, gpu.backend());
        assertEquals(1, gpu.kernels().size());

        GpuKernelCompiler.GpuKernel kernel = gpu.kernels().getFirst();
        assertEquals("add", kernel.sourceName());
        assertEquals(GpuKernelCompiler.ExecutionShape.SINGLE_WORK_ITEM, kernel.executionShape());
        assertEquals("i32", kernel.resultType());
        assertTrue(gpu.source().contains("static inline int ores_dev_math_add_a2"));
        assertTrue(gpu.source().contains("__kernel void ores_kernel_math_add_a2"));
        assertTrue(gpu.source().contains("__global int* restrict __ores_out"));
        assertTrue(gpu.source().contains("__ores_out[0] = ores_dev_math_add_a2(a, b)"));
    }

    @Test
    void compilerAutoParallelizesCanonicalTopLevelBufferLoops() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc scale(Array<f32> mut xs, i64 n, f32 factor) => void {
                    for (let i64 i = 0; i < n; i = i + 1) {
                      xs[i] = xs[i] * factor;
                    }
                    return;
                  }
                end
                """);

        GpuKernelCompiler.GpuKernel kernel = compilation.gpuProgram().kernels().getFirst();
        assertEquals(GpuKernelCompiler.ExecutionShape.DATA_PARALLEL_1D, kernel.executionShape());
        assertEquals("n", kernel.launchExtentExpression());
        assertTrue(compilation.gpuProgram().source().contains("__global float* restrict xs"));
        assertTrue(compilation.gpuProgram().source().contains("get_global_id(0)"));
        assertTrue(compilation.gpuProgram().source().contains("xs[i] = (xs[i] * factor)"));
    }

    @Test
    void gpuParallelListOfLowersToIndependentConcurrentKernelEntries() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module app
                  fnc main() => void {
                    const funcs = gpu parallel List.of(
                      || -> { return; },
                      || -> { return; },
                      || -> { return; }
                    );
                    return;
                  }
                end
                """);

        var kernels = compilation.gpuProgram().kernels();
        assertEquals(3, kernels.size());
        assertTrue(kernels.stream().allMatch(k -> k.kind() == GpuKernelCompiler.KernelKind.LAMBDA));
        assertTrue(kernels.stream().allMatch(k -> k.mode() == Ast.GpuMode.PARALLEL));
        assertNotNull(kernels.getFirst().batchId());
        assertTrue(kernels.stream().allMatch(k -> kernels.getFirst().batchId().equals(k.batchId())));
        assertEquals(3, kernels.stream().map(GpuKernelCompiler.GpuKernel::kernelSymbol).distinct().count());

        assertEquals(1, compilation.gpuProgram().batches().size());
        GpuKernelCompiler.GpuBatch batch = compilation.gpuProgram().batches().getFirst();
        assertEquals(3, batch.workItems());
        assertTrue(batch.hasDispatcher());
        assertTrue(compilation.gpuProgram().source().contains("__kernel void " + batch.dispatcherSymbol()));
        assertTrue(compilation.gpuProgram().source().contains("switch (__ores_task)"));
    }

    @Test
    void autoParallelizerFallsBackWhenMutableBufferAccessesWouldRace() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                define module math
                  gpu fnc unsafe(Array<f32> mut xs, i64 n) => void {
                    for (let i64 i = 0; i < n; i = i + 1) {
                      xs[0] = xs[i];
                    }
                    return;
                  }
                end
                """);

        GpuKernelCompiler.GpuKernel kernel = compilation.gpuProgram().kernels().getFirst();
        assertEquals(GpuKernelCompiler.ExecutionShape.SINGLE_WORK_ITEM, kernel.executionShape());
        assertNull(kernel.launchExtentExpression());
    }

    @Test
    void fp64ExtensionIsRequiredOnlyWhenGeneratedCodeUsesDouble() {
        var ints = OresCompiler.compile("""
                gpu fnc add(i32 a, i32 b) => i32 {
                  return a + b;
                }
                """).gpuProgram();
        assertFalse(ints.requiredExtensions().contains("cl_khr_fp64"));
        assertFalse(ints.source().contains("cl_khr_fp64"));

        var doubles = OresCompiler.compile("""
                gpu fnc add(f64 a, f64 b) => f64 {
                  return a + b;
                }
                """).gpuProgram();
        assertTrue(doubles.requiredExtensions().contains("cl_khr_fp64"));
        assertTrue(doubles.source().contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));
    }

    @Test
    void gpuAbiRejectsArrayReturnsAndRecursiveDeviceCallGraphs() {
        IllegalArgumentException arrayReturn = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(Array<f32> xs) => Array<f32> {
                            return xs;
                          }
                        end
                        """));
        assertTrue(arrayReturn.getMessage().contains("must return a scalar or void"));

        IllegalArgumentException recursion = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc recurse(i32 x) => i32 {
                            if x <= 0; do
                              return 0;
                            fi
                            return recurse(x - 1);
                          }
                        end
                        """));
        assertTrue(recursion.getMessage().contains("recursive GPU call graph"));
    }

    @Test
    void acceptedGpuCodeRejectsHostOnlyAggregatesBeforeLowering() {
        IllegalArgumentException listLiteral = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(i32 x) => i32 {
                            val ys = [x, x + 1];
                            return x;
                          }
                        end
                        """));
        assertTrue(listLiteral.getMessage().contains("cannot allocate list/array literals"));

        IllegalArgumentException forOf = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        define module math
                          gpu fnc bad(Array<i32> xs) => void {
                            for (val x of xs) {
                              return;
                            }
                          }
                        end
                        """));
        assertTrue(forOf.getMessage().contains("for-of is not supported inside GPU code"));
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
