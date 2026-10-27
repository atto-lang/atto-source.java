package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InvertedArrayFailClosedTest {
    @Test
    void unsupportedMemberCannotReachGenericRuntimeDispatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.reserve(8);
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("v1 exposes only push"),
                failure::getMessage);
    }

    @Test
    void bareBracketProjectionCannotReachGenericIndexDispatch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define inverted class Particle as
                          val f64 x;
                        end

                        fnc bad(): void {
                          let mut InvertedArray<Particle> particles =
                              new InvertedArray<Particle>();
                          particles.push(1.0);
                          particles[0];
                          return;
                        }
                        """));

        assertTrue(
                failure.getMessage().contains("row projection cannot escape"),
                failure::getMessage);
    }
}
