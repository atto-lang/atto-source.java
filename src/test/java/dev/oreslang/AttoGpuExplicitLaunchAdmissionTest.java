package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1a: source AST admission only. Physical GPU execution is intentionally
 * rejected until a verified provider, device loan/fence and effect checker exist.
 */
final class AttoGpuExplicitLaunchAdmissionTest {
    private static final String LAUNCH = """
            pub fnc main(): void {
                val pending = launch saxpy(a, x, y, out, n) on gpu(device) {
                    grid: [ceil_div(n, 256), 1, 1],
                    block: [256, 1, 1],
                    queue: queue,
                    shared_bytes: 0
                };
                return;
            }
            """;

    @Test
    void parserBuildsTypedDedicatedLaunchAstWithoutRecognizingCudaChevrons() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(LAUNCH));
        String tree = program.toString();
        assertTrue(tree.contains("GpuLaunchExpr"), tree);
        assertTrue(tree.contains("saxpy"), tree);
        assertTrue(tree.contains("ceil_div"), tree);
        assertTrue(tree.contains("sharedBytes=LiteralExpr[value=0]"), tree);
        assertFalse(tree.contains("TaskExpr"), tree);
    }

    @Test
    void parsedLaunchCannotBecomeHostCallOrCpuTask() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(LAUNCH));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-008"), error::getMessage);
        assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
    }

    @Test
    void invalidBlockDimensionsRejectBeforeGpuRuntimeAdmission() {
        String invalid = LAUNCH.replace("block: [256, 1, 1]", "block: [0, 1, 1]");
        assertDoesNotThrow(() -> Parser.parse(invalid));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(invalid));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-003"), error::getMessage);
    }

    @Test
    void malformedLaunchGeometryAndConfigurationFailParser() {
        for (String bad : List.of(
                LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "grid: [4, 2]"),
                LAUNCH.replace("block: [256, 1, 1]", "block: [256, 1, 1, 2]"),
                LAUNCH.replace("block: [256, 1, 1]", "blocks: [256, 1, 1]"),
                LAUNCH.replace("queue: queue", "queues: queue"),
                LAUNCH.replace("on gpu(device)", "on cpu(device)"),
                LAUNCH.replace("on gpu(device)", "at gpu(device)"),
                LAUNCH.replace("shared_bytes: 0", "unproven: 0"))) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(bad), bad);
        }
    }

    @Test
    void cudaChevronSpellingAndBareAcceleratorUseRemainRejected() {
        for (String source : List.of(
                "pub fnc main(): void { saxpy<<<1, 256>>>(out); return; }",
                "pub fnc main(): void { gpu { return; } return; }",
                "pub fnc main(): void { val x = on gpu(device); return; }",
                "pub gpu fnc kernel(): void { return; }")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void contextualLaunchIdentifierRemainsUsableAsNormalFunctionName() {
        String source = """
                fnc launch(int a): int { return a; }
                pub fnc main(): int { return launch(7); }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
    }

    @Test
    void missingSharedBytesDefaultsToZeroInAstButNotToDeviceExecution() {
        String source = LAUNCH.replace(",\n        shared_bytes: 0", "");
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(source));
        assertTrue(program.toString().contains("GpuLaunchExpr"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void parserCanRepresentQualifiedKernelNameWithoutHostInvocation() {
        String source = LAUNCH.replace("launch saxpy(", "launch MathGpu.saxpy(");
        assertTrue(assertDoesNotThrow(() -> Parser.parse(source)).toString()
                .contains("MemberExpr"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }
}
