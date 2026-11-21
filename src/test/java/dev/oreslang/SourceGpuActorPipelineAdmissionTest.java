package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopKernelIR;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SourceGpuActorPipelineAdmissionTest {
    private static String fixture() throws Exception {
        return Files.readString(Path.of("tests/fixtures/cpu-gpu-collect-pipeline.ores"));
    }
    private static DataOrientedLayout.Plan layout() {
        return new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 7, 3, 4, 16, 4);
    }
    private static DopKernelIR.GpuTarget target() {
        return new DopKernelIR.GpuTarget(64, 4096, 100, true);
    }
    private static String replace(String src, String before, String after) {
        assertTrue(src.contains(before), "fixture unexpectedly changed");
        return src.replace(before, after);
    }

    @Test void trustedJavaAndNativeSourceGraphUseIdenticalStageContracts() throws Exception {
        String source = fixture();
        var plan = OresCompiler.compilePipelineSource(source, layout(), target());
        assertEquals("do_work", plan.entry());
        assertEquals("transform", plan.gpuFunction());
        assertEquals(3, plan.stages().size());
        assertEquals("CpuWorker", plan.stages().get(0).name());
        assertEquals("void", plan.stages().get(0).input());
        assertEquals("f32", plan.stages().get(0).output());
        assertEquals("GpuWorker", plan.stages().get(1).name());
        assertEquals("CollectResultsWorker", plan.stages().get(2).name());
        assertEquals("Results", plan.stages().get(2).output());
        assertEquals(layout().byteLength(), plan.kernel().requiredBytes());
        assertTrue(plan.kernel().deviceSource().contains("__kernel void oreslang_dop_f32"));
        assertEquals(plan, Parser.parseTrustedPipelineSource(source, layout(), target()));
        assertThrows(RuntimeException.class, () -> Parser.parse(source));
    }

    @Test void allUnimplementedGraphSemanticsAreRejected() throws Exception {
        String source = fixture();
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                replace(source, "StreamOperator<f32, f32>", "StreamOperator<void, f32>"),
                layout(), target()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                replace(source, "pipe spawn GpuWorker()", "pipe spawn CollectResultsWorker()"),
                layout(), target()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                replace(source, "return value * 2.0 + 1.0;", "return value * 2.0 + await value;"),
                layout(), target()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                replace(source, "StreamOperator<void, f32> as\nend",
                        "StreamOperator<void, f32> as\n  on_start(): void { return; }\nend"),
                layout(), target()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                replace(source, "spawn GpuWorker()", "spawn GpuWorker(1)"),
                layout(), target()));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compilePipelineSource(
                source + "pub routine untrusted(): void { return; }", layout(), target()));
    }
}
