package dev.oreslang;

import dev.oreslang.parser.ExecutionSurfaceV1;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ExecutionSurfaceV1Test {
    @Test
    void eagerBlocksAndCallablesAreNeverContextuallyAmbiguous() {
        var forms = ExecutionSurfaceV1.inspect("""
                fnc sample(): void {
                  const eager = gpu {};
                  const callable = gpu () {};
                  const typed = gpu (int x) { };
                  pub gpu fnc kernel(): void {}
                }
                """);
        assertEquals(4, forms.size());
        assertEquals(ExecutionSurfaceV1.Form.EAGER_REGION, forms.get(0).form());
        assertEquals(ExecutionSurfaceV1.Form.ANONYMOUS_GPU_KERNEL, forms.get(1).form());
        assertEquals(ExecutionSurfaceV1.Form.ANONYMOUS_GPU_KERNEL, forms.get(2).form());
        assertEquals(ExecutionSurfaceV1.Form.NAMED_GPU_KERNEL, forms.get(3).form());
        assertFalse(forms.get(0).nonblocking());
        // The production AST still rejects unlowered placement even when a
        // compiler-only recognizer has resolved the syntax ambiguity.
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main():void { const x = gpu {}; }"));
    }

    @Test
    void nonblockingNestedRegionsRetainParentChain() {
        var nodes = ExecutionSurfaceV1.inspect("""
                pub routine run(): void {
                    const result = nb gpu {
                        const intermediate = nb cpu {
                            const inner = nb gpu {};
                        };
                    };
                }
                """);
        assertEquals(3, nodes.size());
        assertEquals(ExecutionSurfaceV1.Domain.GPU, nodes.get(0).domain());
        assertEquals(ExecutionSurfaceV1.Domain.CPU, nodes.get(1).domain());
        assertEquals(ExecutionSurfaceV1.Domain.GPU, nodes.get(2).domain());
        for (var node : nodes) assertTrue(node.nonblocking());
        assertEquals(-1, nodes.get(0).parentIndex());
        assertEquals(0, nodes.get(1).parentIndex());
        assertEquals(1, nodes.get(2).parentIndex());
    }

    @Test
    void cpuAndCommentsAndGpuIntrinsicsAreDistinct() {
        var forms = ExecutionSurfaceV1.inspect("""
                // gpu { nb cpu {} }
                fnc demo(): void {
                    const ignored = "nb gpu { cpu {} }";
                    const host = cpu {};
                    const index = gpu.index;
                    // gpu (x) {}
                }
                """);
        assertEquals(2, forms.size());
        assertEquals(ExecutionSurfaceV1.Form.EAGER_REGION, forms.get(0).form());
        assertEquals(ExecutionSurfaceV1.Form.GPU_INTRINSIC, forms.get(1).form());
    }

    @Test
    void malformedAndNonblockingKernelCallablesFailClosed() {
        for (String bad : new String[]{
                "gpu", "gpu (x)", "gpu {", "cpu { gpu {",
                "nb gpu () {}", "nb cpu () {}", "gpu bad",
                "cpu (x) {}", "gpu..index"
        }) assertThrows(IllegalArgumentException.class,
                () -> ExecutionSurfaceV1.inspect(bad), bad);
    }
}
