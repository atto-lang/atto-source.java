package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class NeuralActorSchemaTest {
    @Test
    void modelTensorShapeChecksBytesRankAndOverflow() {
        var shape = new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.BF16, List.of(8L, 128L, 4096L));
        assertEquals(8L * 128 * 4096 * 2, shape.byteLength());
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralActorSchema.TensorShape(
                        NeuralActorSchema.DType.FP32, List.of(Long.MAX_VALUE, 100L)));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralActorSchema.TensorShape(
                        NeuralActorSchema.DType.INT8, List.of(0L, 4L)));
        assertThrows(IllegalArgumentException.class,
                () -> new NeuralActorSchema.TensorShape(
                        NeuralActorSchema.DType.INT8, List.of()));
    }

    @Test
    void tensorDescriptorsAreImmutableNotNativeHandles() {
        var dims = new java.util.ArrayList<>(List.of(4L, 16L));
        var shape = new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.FP32, dims);
        dims.set(0, 1000L);
        assertEquals(List.of(4L, 16L), shape.dimensions());
        assertThrows(UnsupportedOperationException.class,
                () -> shape.dimensions().set(0, 1L));
    }

    @Test
    void gradientMustMatchExactTrainingProvenanceNotJustDtype() {
        var version = new NeuralActorSchema.ModelVersion("my-llm", "ckpt-13", 7);
        var shape = new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.FP32, List.of(4L, 64L));
        var ticket = new NeuralActorSchema.Ticket(
                UUID.randomUUID(), version, 2, 1, 12, 0);
        var forward = new NeuralActorSchema.ForwardBatch(ticket, shape);
        var backward = new NeuralActorSchema.GradientBatch(ticket, shape);
        assertDoesNotThrow(() -> NeuralActorSchema.requireSameStep(forward, backward));
        var stale = new NeuralActorSchema.Ticket(
                ticket.requestId(), version, 2, 1, 13, 0);
        assertThrows(SecurityException.class, () ->
                NeuralActorSchema.requireSameStep(forward,
                        new NeuralActorSchema.GradientBatch(stale, shape)));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuralActorSchema.GradientBatch(new NeuralActorSchema.Ticket(
                        ticket.requestId(), version, 2, 1, -1, 0), shape));
    }

    @Test
    void modelTopologyEpochAndTensorContractAreChecked() {
        var v1 = new NeuralActorSchema.ModelVersion("my-llm", "ckpt-13", 7);
        var v2 = new NeuralActorSchema.ModelVersion("my-llm", "ckpt-13", 8);
        assertNotEquals(v1, v2);
        var expected = new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.BF16, List.of(16L, 256L));
        var bad = new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.FP16, List.of(16L, 256L));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralActorSchema.requireCompatibleTensor(expected, bad));
    }

    @Test
    void roleDefaultsKeepStreamingAndUnarySemanticsIndependentOfTransport() {
        assertThrows(IllegalArgumentException.class, () ->
                NeuralActorSchema.validateReplyMode(
                        NeuralActorSchema.Role.GENERATION, NeuralActorSchema.AstRole.UNARY));
        assertThrows(IllegalArgumentException.class, () ->
                NeuralActorSchema.validateReplyMode(
                        NeuralActorSchema.Role.OPTIMIZER, NeuralActorSchema.AstRole.STREAM));
        assertDoesNotThrow(() -> NeuralActorSchema.validateReplyMode(
                NeuralActorSchema.Role.EXPERT, NeuralActorSchema.AstRole.UNARY));
    }
}
