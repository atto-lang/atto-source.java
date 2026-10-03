package dev.oreslang;

import dev.oreslang.compiler.GpuKernelCompiler;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GpuMatrixCompilerTest {
    @Test
    void lowersDenseMatrixMultiplyToOneWorkItemPerOutputCell() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                gpu fnc matmul(
                  GpuMatrix<f32> a,
                  GpuMatrix<f32> b,
                  GpuMatrix<f32> mut out
                ) => void {
                  out = a @ b;
                  return;
                }
                """);

        GpuKernelCompiler.GpuProgram program = compilation.gpuProgram();
        assertEquals("ores-gpu-abi-v3", program.manifest().lines()
                .findFirst().orElseThrow().substring("abi=".length()));

        GpuKernelCompiler.GpuKernel kernel = program.kernels().getFirst();
        assertEquals(GpuKernelCompiler.ExecutionShape.DATA_PARALLEL_1D, kernel.executionShape());
        assertEquals("__ores_len_out", kernel.launchExtentExpression());
        assertEquals("__ores_len_out", kernel.launchPlan().globalWorkItemsExpression());
        assertTrue(kernel.launchPlan().skipLaunchWhenGlobalWorkItemsZero());
        assertTrue(kernel.launchPlan().enforceNoAliasBuffers());

        assertTrue(kernel.hiddenParameters().stream().anyMatch(p ->
                p.purpose() == GpuKernelCompiler.HiddenPurpose.MATRIX_ROWS
                        && "a".equals(p.sourceParameter())));
        assertTrue(kernel.hiddenParameters().stream().anyMatch(p ->
                p.purpose() == GpuKernelCompiler.HiddenPurpose.MATRIX_COLS
                        && "b".equals(p.sourceParameter())));
        assertTrue(kernel.hiddenParameters().stream().anyMatch(p ->
                p.purpose() == GpuKernelCompiler.HiddenPurpose.MATRIX_ROWS
                        && "out".equals(p.sourceParameter())));

        String source = program.source();
        assertTrue(source.contains("#define ORES_GPU_ABI_VERSION 3"));
        assertTrue(source.contains("#define ORES_GPU_ERR_SHAPE 2"));
        assertTrue(source.contains("__global const float* restrict ores_v_a"));
        assertTrue(source.contains("__global const float* restrict ores_v_b"));
        assertTrue(source.contains("__global float* restrict ores_v_out"));
        assertTrue(source.contains("ulong __ores_rows_a"));
        assertTrue(source.contains("ulong __ores_cols_a"));
        assertTrue(source.contains("ulong __ores_rows_out"));
        assertTrue(source.contains("ulong __ores_cols_out"));
        assertTrue(source.contains("ulong __ores_mat_gid = (ulong)get_global_id(0)"));
        assertTrue(source.contains("for (ulong __ores_mat_k = 0;"));
        assertTrue(source.contains("ores_v_out[__ores_mat_gid] = __ores_mat_acc"));
        assertTrue(source.contains("__ores_cols_a == __ores_rows_b"));
    }

    @Test
    void conciseGpuBlockSupportsMatrixMultiply() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                fnc host() => void {
                  const kernel = gpu (
                    GpuMatrix<f64> a,
                    GpuMatrix<f64> b,
                    GpuMatrix<f64> mut out
                  ) {
                    out = a @ b;
                    return;
                  };
                  return;
                }
                """);

        GpuKernelCompiler.GpuKernel kernel = compilation.gpuProgram().kernels().getFirst();
        assertEquals(GpuKernelCompiler.KernelKind.LAMBDA, kernel.kind());
        assertEquals(GpuKernelCompiler.ExecutionShape.DATA_PARALLEL_1D, kernel.executionShape());
        assertTrue(compilation.gpuProgram().requiredExtensions().contains("cl_khr_fp64"));
    }

    @Test
    void complex64MatrixMultiplyUsesSemanticComplexProduct() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                gpu fnc matmul(
                  GpuMatrix<complex64> a,
                  GpuMatrix<complex64> b,
                  GpuMatrix<complex64> mut out
                ) => void {
                  out = a @ b;
                  return;
                }
                """);

        String source = compilation.gpuProgram().source();
        assertTrue(source.contains("static inline float2 ores_complex_mul_f32"));
        assertTrue(source.contains("ores_complex_mul_f32("));
        assertTrue(source.contains("__global const float2* restrict ores_v_a"));
        assertTrue(source.contains("__global float2* restrict ores_v_out"));
        assertFalse(compilation.gpuProgram().requiredExtensions().contains("cl_khr_fp64"));
    }

    @Test
    void complex128MatrixMultiplyRequiresFp64AndUsesDouble2() {
        OresCompiler.CompilationResult compilation = OresCompiler.compile("""
                fnc host() => void {
                  const kernel = gpu (
                    GpuMatrix<complex128> a,
                    GpuMatrix<complex128> b,
                    GpuMatrix<complex128> mut out
                  ) {
                    out = a @ b;
                    return;
                  };
                  return;
                }
                """);

        String source = compilation.gpuProgram().source();
        assertTrue(source.contains("static inline double2 ores_complex_mul_f64"));
        assertTrue(source.contains("ores_complex_mul_f64("));
        assertTrue(source.contains("#pragma OPENCL EXTENSION cl_khr_fp64 : enable"));
        assertTrue(compilation.gpuProgram().requiredExtensions().contains("cl_khr_fp64"));
    }

    @Test
    void rejectsIntegerMatrixMultiplyUntilOverflowSemanticsAreSpecified() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc matmul(
                          GpuMatrix<i32> a,
                          GpuMatrix<i32> b,
                          GpuMatrix<i32> mut out
                        ) => void {
                          out = a @ b;
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("currently lowers f32/f64/complex64/complex128 matrices"));
    }

    @Test
    void matmulCallableCannotBeInvokedAsDeviceHelper() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compile("""
                        gpu fnc inner(
                          GpuMatrix<f32> a,
                          GpuMatrix<f32> b,
                          GpuMatrix<f32> mut out
                        ) => void {
                          out = a @ b;
                          return;
                        }

                        gpu fnc outer(
                          GpuMatrix<f32> a,
                          GpuMatrix<f32> b,
                          GpuMatrix<f32> mut out
                        ) => void {
                          inner(a, b, out);
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("kernel launch boundary"));
    }

    @Test
    void rejectsMatrixMultiplyOutsideGpuPlacement() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc bad(
                          GpuMatrix<f32> a,
                          GpuMatrix<f32> b
                        ) => void {
                          val product = a @ b;
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("GpuMatrix @ is GPU-only"));
    }

    @Test
    void outputMustBeMutable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        gpu fnc bad(
                          GpuMatrix<f32> a,
                          GpuMatrix<f32> b,
                          GpuMatrix<f32> out
                        ) => void {
                          out = a @ b;
                          return;
                        }
                        """));

        assertTrue(failure.getMessage().contains("cannot reassign"));
    }
}
