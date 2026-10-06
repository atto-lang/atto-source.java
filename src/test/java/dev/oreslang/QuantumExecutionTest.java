package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class QuantumExecutionTest {
    @Test
    void quantumCallablesAreNeverEmittedAsOpenClKernels() {
        var compilation = OresCompiler.compile("""
                define module compute
                  quantum fnc qpu(i32 x) => i32 { return x + 1; }
                  define class QuantumOps as
                    quantum static fnc solve(i32 x) => i32 { return x + 2; }
                  end
                  gpu fnc device(i32 x) => i32 { return x + 3; }
                end
                """);

        var artifact = compilation.gpuProgram();
        assertEquals(1, artifact.kernels().size());
        assertEquals("device", artifact.kernels().getFirst().sourceName());
        assertFalse(artifact.source().contains("qpu"));
        assertFalse(artifact.source().contains("solve"));
        assertFalse(artifact.manifest().contains("QuantumOps"));

        var quantumOnly = OresCompiler.compile("""
                quantum fnc qpu(i32 x) => i32 { return x + 1; }
                """).gpuProgram();
        assertFalse(quantumOnly.hasKernels());
        assertTrue(quantumOnly.batches().isEmpty());
    }

    @Test
    void quantumPlacementInvalidatesExportedFunctionAndStaticMethodAbi() {
        for (String declaration : new String[] {
                "pub %sfnc solve(i32 x) => i32 { return x; }",
                "define class Ops as pub %sstatic fnc solve(i32 x) => i32 { return x; } end"
        }) {
            IncrementalCompiler compiler = new IncrementalCompiler();
            String dependent = "import * as api from \"./api.ores\"; pub routine main() => void { return; }";
            var first = compiler.compile(Map.of(
                    "api.ores", declaration.formatted(""), "consumer.ores", dependent));
            var quantum = compiler.compile(Map.of(
                    "api.ores", declaration.formatted("quantum "), "consumer.ores", dependent));

            assertNotEquals(first.units().get("api.ores").abiDigest(),
                    quantum.units().get("api.ores").abiDigest());
            assertTrue(quantum.rebuilt("consumer.ores"));
            assertFalse(quantum.units().get("api.ores").gpuProgram().hasKernels());

            var bodyChanged = compiler.compile(Map.of(
                    "api.ores", declaration.formatted("quantum ").replace("return x;", "return x + 1;"),
                    "consumer.ores", dependent));
            assertTrue(bodyChanged.reused("consumer.ores"));
        }
    }

    @Test
    void quantumStaticInvocationFailsBeforeEnteringItsBody() throws Exception {
        String program = """
                define class QuantumOps as
                  quantum static fnc solve(i32 x) => i32 { return x / 0; }
                end
                pub fnc main() => void { QuantumOps.solve(1); return; }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> context.eval(OresLanguage.ID, program));
            assertTrue(failure.getMessage().contains("no quantum lowering/backend"));
            assertTrue(failure.getMessage().contains("QuantumOps.solve"));
        }
    }

    @Test
    void parsesQuantumAsDistinctExecutionTarget() {
        Ast.Program program = Parser.parse("""
                define module compute
                  fnc cpu(i32 x) => i32 {
                    return x;
                  }

                  gpu fnc gpuKernel(i32 x) => i32 {
                    return x;
                  }

                  quantum fnc qpuKernel(i32 x) => i32 {
                    return x;
                  }
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl cpu = (Ast.FunctionDecl) module.declarations().get(0);
        Ast.FunctionDecl gpu = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.FunctionDecl quantum = (Ast.FunctionDecl) module.declarations().get(2);

        assertEquals(Ast.ExecutionTarget.CPU, Ast.executionTarget(cpu.annotations()));
        assertEquals(Ast.ExecutionTarget.GPU, Ast.executionTarget(gpu.annotations()));
        assertEquals(Ast.ExecutionTarget.QUANTUM, Ast.executionTarget(quantum.annotations()));
        assertTrue(Ast.hasQuantumPlacement(quantum.annotations()));
        assertFalse(Ast.hasGpuPlacement(quantum.annotations()));
    }

    @Test
    void quantumIsReservedAndCannotBeUsedAsIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main() => void {
                    const quantum = 1;
                    return;
                  }
                end
                """));
    }

    @Test
    void rejectsMixedOrAsyncQuantumPlacement() {
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  gpu quantum fnc bad(i32 x) => i32 {
                    return x;
                  }
                end
                """));
        assertTrue(mixed.getMessage().contains("mutually exclusive execution targets"));

        IllegalArgumentException async = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  quantum async fnc bad(i32 x) => i32 {
                    return x;
                  }
                end
                """));
        assertTrue(async.getMessage().contains("quantum")
                && async.getMessage().contains("async"));
    }

    @Test
    void rejectsQuantumRoutineAndInstanceMethod() {
        IllegalArgumentException routine = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  quantum routine bad() => void {
                    return;
                  }
                end
                """));
        assertTrue(routine.getMessage().contains("fnc, not routine"));

        IllegalArgumentException instance = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  define class Device as
                    quantum run() => void {
                      return;
                    }
                  end
                end
                """));
        assertTrue(instance.getMessage().contains("quantum instance methods"));
    }

    @Test
    void parsesQuantumStaticFunctionUsingCanonicalClassBodySyntax() {
        Ast.Program program = Parser.parse("""
                define module app
                  define class QuantumOps as
                    quantum static fnc solve(i32 shots) => i32 {
                      return shots;
                    }
                  end
                end
                """);

        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        Ast.MethodDecl method = klass.methods().getFirst();
        assertTrue(method.isStatic());
        assertEquals(Ast.ExecutionTarget.QUANTUM, Ast.executionTarget(method.annotations()));
    }

    @Test
    void quantumExecutionNeverFallsBackToCpuOrGpuInterpreter() throws Exception {
        String program = """
                define module app
                  quantum fnc kernel(i32 x) => i32 {
                    return x + 1;
                  }

                  pub fnc main() => void {
                    kernel(41);
                    return;
                  }
                end
                """;

        Source source = Source.newBuilder(OresLanguage.ID, program, "quantum-no-fallback.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .build()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains("no quantum lowering/backend")
                    || failure.getMessage().contains("never falls back to CPU or GPU"));
        }
    }
}
