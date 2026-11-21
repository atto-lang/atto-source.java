package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Source-level CPU Task placement and GPU fail-closed admission. */
final class PlacedTaskLanguageTest {

    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "placed-task.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void cpuTaskUsesExistingSchedulerAndReturnsTypedValue() throws Exception {
        String program = """
                pub routine main(): void {
                    let Task<int> job = cpu task { return 40 + 2; };
                    let int result = await job;
                    stdio.stdout.write(result);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void cpuTaskCanInferTaskResultType() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    val job = cpu task { return 42; };
                    let int result = await job;
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void cpuTaskCanUseExplicitFutureCompletionHandle() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    let Future<int> job = cpu task { return 42; };
                    let int result = await job;
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void cpuTaskCannotReturnWrongType() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let Future<int> job = cpu task { return "wrong"; };
                            let int result = await job;
                            return;
                        }
                        """));
    }

    @Test
    void gpuTaskIsARecognizedButUnexecutableSourceDomain() {
        // The parser retains a typed placement, rather than accepting and
        // silently dispatching its body through the CPU Task machinery.
        String program = """
                pub routine main(): void {
                    let Task<int> job = gpu task { return 42; };
                    let int result = await job;
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(program));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(denied.getMessage().contains("GPU-to-CPU fallback is forbidden"),
                denied.getMessage());
    }

    @Test
    void gpuTaskIsRejectedEvenIfNeverAwaited() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            gpu task { return 42; };
                            return;
                        }
                        """));
    }

    @Test
    void eagerCpuRegionExecutesInlineAndReturnsValue() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    let int value = cpu {
                        val int first = 40;
                        return first + 2;
                    };
                    stdio.stdout.write(value);
                    return;
                }
                """));
    }

    @Test
    void cpuRegionReturnDoesNotEscapeEnclosingRoutine() throws Exception {
        assertEquals("12", run("""
                fnc compute(): int {
                    let int partial = cpu { return 7; };
                    return partial + 5;
                }
                pub routine main(): void {
                    stdio.stdout.write(compute());
                    return;
                }
                """));
    }

    @Test
    void eagerCpuRegionMayExecuteForEffectsWithoutReturn() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    cpu { stdio.stdout.write(42); };
                    return;
                }
                """));
    }

    @Test
    void cpuRegionInsideTaskPreservesTransitiveCaptureProof() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                    let int source = 41;
                    let Task<int> worker = task {
                        return cpu { return source + 1; };
                    };
                    let int result = await worker;
                    stdio.stdout.write(result);
                    return;
                }
                """));
    }

    @Test
    void eagerCpuRegionCannotReturnWrongTypeOrFallThrough() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = cpu { return "not an int"; };
                            return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int value = cpu { val int local = 1; };
                            return;
                        }
                        """));
    }

    @Test
    void eagerCpuRegionRejectsAwaitAndExplicitCooperation() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let Task<int> work = task { return 42; };
                            let int value = cpu { return await work; };
                            return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            cpu { rt cooperate; };
                            return;
                        }
                        """));
    }

    @Test
    void eagerGpuRegionIsParsedButRequiresRealDeviceBackend() {
        String source = """
                pub routine main(): void {
                    gpu { return 1; };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("CPU fallback is forbidden"),
                failure.getMessage());
    }

    @Test
    void unloweredNbAndQuantumRegionsStillRejectFailClosed() {
        for (String region : new String[] {
                "nb cpu { return 1; }",
                "nb gpu { return 1; }",
                "quantum task { return 1; }"
        }) {
            String source = "pub routine main(): void { " + region + "; return; }";
            assertThrows(RuntimeException.class, () -> Parser.parse(source), region);
        }
    }

    @Test
    void explicitCpuOffloadCannotSilentlyUseActorCarrier() {
        String program = """
                isoactor PlacementActor {
                    pub fnc attempt(): void {
                        let Task<int> worker = cpu task { return 42; };
                        return;
                    }
                }
                """;
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(denied.getMessage().contains("cpu task cannot offload from an actor"),
                denied.getMessage());
    }

    @Test
    void eagerGpuRegionCannotBeHiddenInsideCpuRegion() {
        String source = """
                pub routine main(): void {
                    let int value = cpu {
                        return gpu { return 42; };
                    };
                    return;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(denied.getMessage().contains("CPU fallback is forbidden"),
                denied.getMessage());
    }

    @Test
    void eagerCpuRegionRejectsBorrowedResult() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let int number = 7;
                            val borrowed = cpu { return rt borrow number; };
                            return;
                        }
                        """));
    }

    @Test
    void nestedGpuPlacementCannotBeHiddenInsideCpuTask() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let Task<int> outer = cpu task {
                                let Task<int> inner = gpu task { return 3; };
                                return await inner;
                            };
                            return;
                        }
                        """));
    }
}
