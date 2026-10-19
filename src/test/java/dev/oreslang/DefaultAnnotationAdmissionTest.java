package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** @Default remains a fail-closed semantic annotation until checked lowering. */
final class DefaultAnnotationAdmissionTest {
    @Test
    void declarationLevelDefaultCannotBeSilentlyIgnored() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Default(7)
                        pub fnc foo(int x): int { return x; }
                        """));
        assertTrue(failure.getMessage().contains("@Default requires compile-time semantic validation"),
                failure.getMessage());
    }

    @Test
    void parameterAnnotationMustNotBeAcceptedAsAnInertStructuralAnnotation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc foo(@Default(7) int x): int { return x; }
                        """));
        assertTrue(failure.getMessage().contains("only @Structural is currently supported"),
                failure.getMessage());
    }

    @Test
    void constructorParameterDefaultIsNotYetExecutable() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Box as
                  val int id;
                  constructor(@Default(4) int id) {
                    self.id = id;
                  }
                end
                """));
    }

    @Test
    void ordinaryOptionTypeInLegacySourceRemainsRequired() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc takes(Option<int> id): void { return; }
                """));
    }
}
