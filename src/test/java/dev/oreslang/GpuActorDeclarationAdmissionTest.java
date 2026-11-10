package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class GpuActorDeclarationAdmissionTest {
    @Test
    void gpuActorDeclarationsFailClosedBeforeOrdinaryActorLowering() {
        for (String source : new String[] {
                "define gpu actor Particle as end",
                "define pub gpu actor Particle as end",
                "define module Simulation as define gpu actor Particle as end end",
                "define gpu actor Particle as state { let int x: 0; } end",
                "define gpu actor Particle { receive(): void { return; } }"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("gpu actor declarations require checked GPU actor"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("no implicit CPU fallback"),
                    error::getMessage);
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
    void ordinaryActorSyntaxDoesNotBecomeGpuActor() {
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Worker as
                    pub receive(): void { return; }
                end
                """));
    }
}
