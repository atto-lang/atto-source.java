package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.hat.HatCpuReferenceExecutor;
import dev.oreslang.hat.HatKernelIr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class HatKernelCompilerTest {
    private static final String ADD = """
            define pub kernel Add as
              fnc main(int left, int right): int {
                return left + right;
              }
            end
            """;

    @Test
    void lowersCanonicalKernelToStableVersionedIr() {
        List<HatKernelIr.Kernel> kernels = OresCompiler.compileHatKernels(ADD);
        assertEquals(1, kernels.size());
        HatKernelIr.Kernel kernel = kernels.getFirst();
        assertEquals(HatKernelIr.ABI_VERSION, kernel.abiVersion());
        assertEquals("__root__.Add", kernel.qualifiedName());
        assertEquals(2, kernel.entry().parameters().size());
        assertEquals("main", kernel.entry().name());

        String canonical = HatKernelIr.canonical(kernel);
        assertTrue(canonical.startsWith("ores-hat-ir-v1\n"));
        assertTrue(canonical.contains("ADD_I64"));
        assertEquals(HatKernelIr.sha256(kernel), HatKernelIr.sha256(kernel));
        assertEquals(64, HatKernelIr.sha256(kernel).length());
    }

    @Test
    void cpuReferenceExecutorRunsPortableIr() {
        HatKernelIr.Kernel kernel = OresCompiler.compileHatKernels(ADD).getFirst();
        assertEquals(42L, HatCpuReferenceExecutor.execute(kernel, 19L, 23L));
        assertEquals(-7L, HatCpuReferenceExecutor.execute(kernel, List.of(-10L, 3L)));
    }

    @Test
    void supportsCheckedScalarArithmeticOnly() {
        HatKernelIr.Kernel kernel = OresCompiler.compileHatKernels("""
                define kernel Arithmetic as
                  fnc main(int a, int b): int {
                    return -(a * b - 2);
                  }
                end
                """).getFirst();
        assertEquals(-10L, HatCpuReferenceExecutor.execute(kernel, 3L, 4L));
        assertThrows(ArithmeticException.class,
                () -> HatCpuReferenceExecutor.execute(
                        OresCompiler.compileHatKernels(ADD).getFirst(), Long.MAX_VALUE, 1L));
    }

    @Test
    void rejectsFloatLiteralsEvenWhenMathematicallyIntegral() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.compileHatKernels("""
                        define kernel BadFloat as
                          fnc main(): int {
                            return 1.0;
                          }
                        end
                        """));
        assertTrue(failure.getMessage().contains("only source int literals"));
    }

    @Test
    void rejectsCallsStateAndUnsupportedKernelEffects() {
        IllegalArgumentException call = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileHatKernels("""
                        define kernel Bad as
                          fnc main(int a): int {
                            return helper(a);
                          }
                        end
                        fnc helper(int a): int { return a; }
                        """));
        assertTrue(call.getMessage().contains("unsupported kernel expression"));

        IllegalArgumentException binding = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileHatKernels("""
                        define kernel Bad as
                          fnc main(int a): int {
                            const b = a + 1;
                            return b;
                          }
                        end
                        """));
        assertTrue(binding.getMessage().contains("exactly one value-return"));
    }

    @Test
    void rejectsMissingOrAmbiguousEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileHatKernels("define kernel Empty as end"));

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileHatKernels("""
                        define kernel Many as
                          fnc main(int a): int { return a; }
                          fnc helper(int a): int { return a; }
                        end
                        """));
    }

    @Test
    void libraryTreeShakingPreservesPublicKernelArtifacts() {
        var build = OresCompiler.compileForBuild(ADD, BuildOptions.library(Map.of()));
        assertTrue(build.retained("__root__.Add"));
        assertEquals(1, build.program().modules().size());
        var declarations = build.program().modules().getFirst().declarations();
        assertEquals(1, declarations.size());
        assertInstanceOf(dev.oreslang.ast.Ast.KernelDecl.class, declarations.getFirst());

        var kernels = dev.oreslang.hat.HatKernelCompiler.compile(build.program());
        assertEquals(1, kernels.size());
        assertEquals(42L, HatCpuReferenceExecutor.execute(kernels.getFirst(), 20L, 22L));
    }

    @Test
    void ordinaryCallDispatchCannotTreatKernelAsFunction() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define kernel Add as
                          fnc main(int a, int b): int { return a + b; }
                        end
                        fnc misuse(): int { return Add(1, 2); }
                        """));
    }
}
