package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser admission is not source-level supervisor execution.
 * Both compilation entry points must reject unlowered CONTROL callbacks.
 */
final class SourceSupervisorAdmissionTest {
    private static final String VALID = """
            define supervisor RootSupervisor as
              @Implements
              pub async on_panic(f: Failure): void {
                return;
              }
              tear_down(): void { return; }
            end
            """;

    @Test
    void typedSupervisorIsDistinctFromActorOrClass() {
        Ast.Program source = Parser.parse(VALID);
        Ast.Decl decl = source.modules().getFirst().declarations().getFirst();
        Ast.SupervisorDecl supervisor = assertInstanceOf(Ast.SupervisorDecl.class, decl);
        assertEquals("RootSupervisor", supervisor.name());
        assertEquals(2, supervisor.hooks().size());
        assertTrue(supervisor.hooks().getFirst().async());
        assertEquals("Failure",
                supervisor.hooks().getFirst().parameters().getFirst().type().name());
    }

    @Test
    void moduleScopedSupervisorRemainsTypedAndNonExecutable() {
        String module = """
                define module Services as
                  define supervisor WorkerSupervisor as
                    on_panic(f: Failure): void { return; }
                  end
                end
                """;
        Ast.Program parsed = Parser.parse(module);
        Ast.SupervisorDecl supervisor = assertInstanceOf(
                Ast.SupervisorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        assertEquals("WorkerSupervisor", supervisor.name());
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(module));
    }

    @Test
    void compilerMustNotSilentlyExecuteUnloweredGuestHook() {
        IllegalArgumentException normal = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(VALID));
        assertTrue(normal.getMessage().contains("compiler-lowered CONTROL turns"));
        IllegalArgumentException diagnostics = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckWithDiagnostics(VALID));
        assertTrue(diagnostics.getMessage().contains("compiler-lowered CONTROL turns"));
    }

    @Test
    void directTypeCheckerAndIsolateEntryPointsAlsoFailClosed() {
        Ast.Program parsed = Parser.parse(VALID);
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(parsed));
        assertThrows(SecurityException.class,
                () -> dev.oreslang.runtime.CapabilityChecker.check(
                        parsed, dev.oreslang.runtime.IsolatePolicy.developer()));
    }

    @Test
    void forgedOrAmbiguousSupervisorHooksAreRejected() {
        for (String source : new String[] {
                "define supervisor S as end",
                "define supervisor S as on_panic(f: String): void { return; } end",
                "define supervisor S as on_panic(f: Failure): void { return; } on_panic(f: Failure): void { return; } end",
                "define supervisor S as let count: int = 0; on_panic(f: Failure): void { return; } end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
