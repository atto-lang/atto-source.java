package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GpuActorDeclarationAdmissionTest {
    private static Ast.GpuActorDecl onlyGpuActor(String source) {
        Ast.Program program = Parser.parse(source);
        List<Ast.Decl> declarations = program.modules().stream()
                .flatMap(m -> m.declarations().stream()).toList();
        assertEquals(1, declarations.size());
        return assertInstanceOf(Ast.GpuActorDecl.class, declarations.getFirst(),
                "GPU actor MUST NOT lower into ordinary ClassDecl");
    }

    @Test
    void sealedGpuActorIsDistinctParserAst() {
        Ast.GpuActorDecl actor = onlyGpuActor("""
                define gpu actor Particle as
                    state {
                        let int position: 0;
                        let int velocity: 1;
                    }
                    step(int dt) -> int ::
                        return dt;
                    end
                end
                """);
        assertEquals("Particle", actor.name());
        assertEquals(Ast.Visibility.PRIVATE, actor.visibility());
        assertEquals(List.of("position", "velocity"),
                actor.stateFields().stream().map(Ast.ProtectedStateField::name).toList());
        assertEquals(1, actor.methods().size());
        assertEquals("step", actor.methods().getFirst().name());
        assertEquals(actor.methods().getFirst().bodyTokens().size(),
                actor.methods().getFirst().bodyTokenKinds().size());
        assertFalse(actor.methods().getFirst().bodyTokens().isEmpty());
    }

    @Test
    void gpuActorSupportsPublicAndModuleDeclarationsWithoutCpuDesugaring() {
        Ast.GpuActorDecl actor = onlyGpuActor("""
                define module Simulation as
                    define pub gpu actor Particle as
                        state { let int x: 0; }
                    end
                end
                """);
        assertEquals(Ast.Visibility.PUBLIC, actor.visibility());
    }

    @Test
    void typeCheckingMustRejectGpuActorBeforeNormalRuntimeLowering() {
        for (String source : List.of(
                "define gpu actor Particle as state {} end",
                "define pub gpu actor Particle as state { let int x: 0; } end",
                "define module Sim as define gpu actor P as state {} end end")) {
            Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse(source), source);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(parsed), source);
            assertTrue(failure.getMessage().contains("GPU_ACTOR_MIR_UNSUPPORTED"),
                    failure::getMessage);
            assertTrue(failure.getMessage().contains("no implicit CPU fallback"),
                    failure::getMessage);
        }
    }

    @Test
    void unsafeMalformedGpuActorDeclarationsFailBeforeCpuExecution() {
        for (String source : List.of(
                "define gpu actor Particle as end",
                "define gpu actor Particle as state { let int x: 0; let int x: 1; } end",
                "define gpu actor Particle as state { let int x: 0; } step() -> void :: end end",
                "pub gpu fnc kernel(): int { return 1; }",
                "gpu actor Particle as state {} end")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void gpuActorSpellingInsideCommentsAndStringsDoesNotCreateDeclaration() {
        assertDoesNotThrow(() -> Parser.parse("""
                // define gpu actor NotARealActor as end
                pub fnc main(): int {
                    const label = "define gpu actor NotARealActor as end";
                    return 42;
                }
                """));
    }

    @Test
    void ordinaryActorSyntaxRemainsOrdinaryActor() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse("""
                define actor Worker as
                    pub receive(): void { return; }
                end
                """));
        assertInstanceOf(Ast.ClassDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
    }
}
