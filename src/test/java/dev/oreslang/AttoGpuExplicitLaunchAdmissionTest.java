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
    void negativeGridAndBlockConstantsRejectAtTypeBoundary() {
        for (String source : List.of(
                LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "grid: [-1, 1, 1]"),
                LAUNCH.replace("block: [256, 1, 1]", "block: [-1, 1, 1]"))) {
            assertDoesNotThrow(() -> Parser.parse(source));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(error.getMessage().contains("GPU-LAUNCH-003"), error::getMessage);
        }
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
    void logicalOverGroupOneDimensionalLaunchPreservesLogicalExtent() {
        String source = LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "over: [n]")
                .replace("block: [256, 1, 1]", "group: [256]");
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(source));
        String tree = program.toString();
        assertTrue(tree.contains("GpuOverGeometry"), tree);
        assertFalse(tree.contains("GpuGridGeometry"), tree);
        assertTrue(tree.contains("over=[NameExpr[name=n], LiteralExpr[value=1], LiteralExpr[value=1]]"), tree);
        assertTrue(tree.contains("group=[LiteralExpr[value=256], LiteralExpr[value=1], LiteralExpr[value=1]]"), tree);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("GPU-LAUNCH-008"), error::getMessage);
        assertTrue(error.getMessage().contains("no implicit CPU fallback"), error::getMessage);
    }

    @Test
    void twoDimensionalLogicalExtentPadsThirdAxisToOne() {
        String source = LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "over: [width, height]")
                .replace("block: [256, 1, 1]", "group: [16, 16]");
        String tree = assertDoesNotThrow(() -> Parser.parse(source)).toString();
        assertTrue(tree.contains("GpuOverGeometry"), tree);
        assertTrue(tree.contains("over=[NameExpr[name=width], NameExpr[name=height], LiteralExpr[value=1]]"), tree);
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void zeroAndNegativeLogicalGeometryIsRejectedAtTypeBoundary() {
        String logical = LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "over: [n]")
                .replace("block: [256, 1, 1]", "group: [256]");
        for (String invalid : List.of(
                logical.replace("group: [256]", "group: [0]"),
                logical.replace("group: [256]", "group: [-1]"),
                logical.replace("over: [n]", "over: [-1]"))) {
            assertDoesNotThrow(() -> Parser.parse(invalid), invalid);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(invalid));
            assertTrue(error.getMessage().contains("GPU-LAUNCH-003"), error::getMessage);
        }
    }

    @Test
    void logicalAndPhysicalGeometryCannotBeMixedOrSilentlyAutoTuned() {
        String logical = LAUNCH.replace("grid: [ceil_div(n, 256), 1, 1]", "over: [n]")
                .replace("block: [256, 1, 1]", "group: [256]");
        for (String bad : List.of(
                logical.replace("group: [256]", "block: [256, 1, 1]"),
                logical.replace("over: [n]", "grid: [1, 1, 1]"),
                logical.replace("group: [256]", "group: auto"),
                logical.replace("over: [n]", "over: []"),
                logical.replace("over: [n]", "over: [1, 2, 3, 4]"))) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(bad), bad);
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
