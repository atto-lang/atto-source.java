package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.GpuKernelSourceHirV1;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** GPU source typing tests: these do not execute .atto guest code or hardware. */
final class GpuKernelSourceHirV1Test {
    private static final String SAXPY = """
            pub gpu fnc saxpy(
                f32 alpha,
                GpuReadBuffer<f32> x,
                GpuReadBuffer<f32> y,
                GpuWriteBuffer<f32> out,
                int n
            ): void {
                const int i = gpu.global_id(0);
                if i < n {
                    out[i] = alpha * x[i] + y[i];
                } fi
            }
            """;

    private static Ast.GpuKernelDecl kernel(String source) {
        Ast.Program parsed = Parser.parse(source);
        assertEquals(1, parsed.modules().size());
        List<Ast.Decl> declarations = parsed.modules().getFirst().declarations();
        assertEquals(1, declarations.size());
        return assertInstanceOf(Ast.GpuKernelDecl.class, declarations.getFirst());
    }

    @Test void kernelIsNotAnOrdinaryCpuFunction() {
        var ast = kernel(SAXPY);
        assertEquals("saxpy", ast.signature().name());
        assertFalse(((Ast.Decl) ast) instanceof Ast.FunctionDecl);
        var plan = GpuKernelSourceHirV1.lower(ast);
        assertEquals("saxpy", plan.symbol());
        assertEquals(5, plan.parameters().size());
        assertEquals(GpuKernelSourceHirV1.Type.BUFFER_WRITE_F32,
                plan.parameters().get(3).type());
        assertEquals(GpuKernelSourceHirV1.Evidence.TYPED_SOURCE_ONLY_NOT_EXECUTABLE,
                plan.evidence());
        assertFalse(plan.executable());
    }

    @Test void typedSaxpyBodyCapturesIndexGuardAndStoreWithoutHostCall() {
        var plan = GpuKernelSourceHirV1.lower(kernel(SAXPY));
        assertEquals(2, plan.body().size());
        assertEquals("LET", plan.body().getFirst().opcode());
        assertEquals("gpu.global_id", plan.body().getFirst().value().opcode());
        assertEquals("IF", plan.body().get(1).opcode());
        assertEquals(GpuKernelSourceHirV1.Type.BOOL,
                plan.body().get(1).value().type());
        assertEquals("STORE_F32", plan.body().get(1).body().getFirst().opcode());
        assertEquals("out", plan.body().get(1).body().getFirst().target());
    }

    @Test void sourceHirNeverGrantsGpuExecutionToProductionCompiler() {
        var error = assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck(SAXPY));
        assertTrue(error.getMessage().contains("GPU-KERNEL-008"), error::getMessage);
        assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
    }

    @Test void aHostCallInKernelIsRejectedBeforeDispatchGate() {
        String invalid = SAXPY.replace("gpu.global_id(0)", "hostCall()");
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(invalid));
        assertTrue(error.getMessage().contains("GPU-KERNEL-006"), error::getMessage);
    }

    @Test void aReadOnlyDeviceViewCanNeverBeStoreTarget() {
        String invalid = SAXPY.replace("GpuWriteBuffer<f32> out", "GpuReadBuffer<f32> out");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-005"), error::getMessage);
    }

    @Test void aWriteOnlyDeviceViewCannotBeLoaded() {
        String invalid = SAXPY.replace("GpuReadBuffer<f32> x", "GpuWriteBuffer<f32> x");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-005"), error::getMessage);
    }

    @Test void scalarTypeMismatchIsNeverImplicitlyConverted() {
        String invalid = SAXPY.replace("f32 alpha", "int alpha");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-002"), error::getMessage);
    }

    @Test void boolIndexIsNotTreatedAsInteger() {
        String invalid = SAXPY.replace("out[i] =", "out[true] =");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-002"), error::getMessage);
    }

    @Test void awaitAndHostSuspensionCannotAppearInKernelBody() {
        String invalid = SAXPY.replace("gpu.global_id(0)", "await pending");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-006"), error::getMessage);
    }

    @Test void deviceBuiltinRejectsUnsupportedAxis() {
        String invalid = SAXPY.replace("gpu.global_id(0)", "gpu.global_id(3)");
        var error = assertThrows(IllegalArgumentException.class,
                () -> GpuKernelSourceHirV1.lower(kernel(invalid)));
        assertTrue(error.getMessage().contains("GPU-KERNEL-006"), error::getMessage);
    }

    @Test void nonGpuDeclarationDoesNotInheritKernelIdentity() {
        String host = SAXPY.replace("pub gpu fnc", "pub fnc");
        Ast.Program program = Parser.parse(host);
        assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test void unsupportedGpuDeclarationModifiersFailParsing() {
        for (String prefix : List.of("suspends", "async", "generator", "nlex")) {
            String invalid = SAXPY.replace("pub gpu fnc", "pub " + prefix + " gpu fnc");
            var err = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(invalid));
            assertTrue(err.getMessage().contains("GPU-KERNEL-006"), err::getMessage);
        }
    }
}
