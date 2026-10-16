package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serialization and supervisor CONTROL authorization must be compiler-derived.
 * User annotations are not runtime codec registration or Sendable evidence.
 */
final class SerdeAnnotationFailClosedParityTest {
    @Test
    void unsupportedSerializationAnnotationsCannotBeInertMetadata() {
        for (String annotation : new String[] {
                "Sendable", "Serializable", "NetworkCodec",
                "ActorMessageCodec", "SupervisorControlCodec"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(("""
                            @%s
                            define class Payload as
                              pub val int value = 7;
                            end
                            """).formatted(annotation)),
                    annotation + " must not silently authorize a codec");
            assertTrue(error.getMessage().contains("requires compile-time semantic validation"),
                    () -> annotation + ": " + error.getMessage());
        }
    }
}
