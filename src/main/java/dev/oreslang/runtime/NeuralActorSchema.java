package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-side validation schema for proposed model/LLM actor families.
 * These are untrusted descriptors, NOT tensor handles or GPU capabilities.
 * No native tensor storage is allocated and no guest type keyword is enabled.
 */
public final class NeuralActorSchema {
    private NeuralActorSchema() {}

    public enum Role {
        MODEL, LLM, TOKENIZER, EMBEDDING, ATTENTION_STAGE, FEEDFORWARD_STAGE,
        EXPERT_ROUTER, EXPERT, EXPERT_COMBINE, KV_CACHE, SAMPLER, GENERATION,
        RECURRENT_MEMORY, TRAINING_STAGE, LOSS, OPTIMIZER, CHECKPOINT
    }

    public enum DType {
        FP32(4), FP16(2), BF16(2), INT8(1), INT32(4);
        private final int bytes;
        DType(int bytes) { this.bytes = bytes; }
        public int bytes() { return bytes; }
    }

    /** Model identity and replacement generation must be checked together. */
    public record ModelVersion(String modelId, String checkpoint, long topologyEpoch) {
        public ModelVersion {
            if (modelId == null || !modelId.matches("[A-Za-z0-9_.-]{1,128}")
                    || checkpoint == null || !checkpoint.matches("[A-Za-z0-9_.:-]{1,128}")
                    || topologyEpoch < 0) {
                throw new IllegalArgumentException("invalid model identity/version/topology generation");
            }
        }
    }

    /**
     * Model-compatible tensor *shape*, never a live buffer or serializable GPU
     * pointer. The native tensor runtime creates and authenticates actual
     * leases. Validate byte products before admission/placement.
     */
    public record TensorShape(DType dtype, List<Long> dimensions) {
        private static final int MAX_RANK = 8;
        public TensorShape {
            Objects.requireNonNull(dtype);
            dimensions = List.copyOf(Objects.requireNonNull(dimensions));
            if (dimensions.isEmpty() || dimensions.size() > MAX_RANK) {
                throw new IllegalArgumentException("tensor rank must be 1..8");
            }
            for (long dimension : dimensions) {
                if (dimension < 1) throw new IllegalArgumentException("tensor dimensions must be positive");
            }
            byteLength(dtype, dimensions);
        }
        public long byteLength() { return byteLength(dtype, dimensions); }
        private static long byteLength(DType dtype, List<Long> dims) {
            try {
                long total = dtype.bytes();
                for (long d : dims) total = Math.multiplyExact(total, d);
                return total;
            } catch (ArithmeticException tooLarge) {
                throw new IllegalArgumentException("tensor byte size overflows signed 64-bit", tooLarge);
            }
        }
    }

    /** Step -1 marks inference; >=0 marks a specific training step. */
    public record Ticket(
            UUID requestId, ModelVersion model, long batchId, long microbatchId,
            long trainingStep, long sequenceOffset) {
        public Ticket {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(model);
            if (batchId < 0 || microbatchId < 0 || trainingStep < -1 || sequenceOffset < 0) {
                throw new IllegalArgumentException("invalid batch, microbatch, step or sequence position");
            }
        }
        public boolean training() { return trainingStep >= 0; }
    }

    public record ForwardBatch(Ticket ticket, TensorShape activations) {
        public ForwardBatch {
            Objects.requireNonNull(ticket);
            Objects.requireNonNull(activations);
        }
    }

    public record GradientBatch(Ticket ticket, TensorShape gradients) {
        public GradientBatch {
            Objects.requireNonNull(ticket);
            Objects.requireNonNull(gradients);
            if (!ticket.training()) {
                throw new IllegalArgumentException("gradient batch requires a training-step ticket");
            }
        }
    }

    /**
     * Validates pipeline provenance. Gradient dimensions are often DIFFERENT
     * from output activation dimensions; compare them to the receiving
     * stage's *input* tensor spec separately, not to this output shape.
     */
    public static void requireSameStep(ForwardBatch forward, GradientBatch backward) {
        Objects.requireNonNull(forward);
        Objects.requireNonNull(backward);
        if (!forward.ticket().training()
                || !forward.ticket().equals(backward.ticket())) {
            throw new SecurityException("gradient does not match forward model/topology/step/microbatch ticket");
        }
    }

    public static void requireCompatibleTensor(TensorShape expected, TensorShape actual) {
        if (!Objects.requireNonNull(expected).equals(Objects.requireNonNull(actual))) {
            throw new IllegalArgumentException("neural tensor dtype/shape mismatch");
        }
    }

    /**
     * A legal role/reply pair is not a claim that kernels or guest syntax are
     * ready. Streaming generation must never be represented as an implicit
     * unary response with unbounded token values.
     */
    public static void validateReplyMode(Role role, AstRole reply) {
        Objects.requireNonNull(role);
        Objects.requireNonNull(reply);
        if (role == Role.GENERATION && reply != AstRole.STREAM) {
            throw new IllegalArgumentException("generation actor requires STREAM reply protocol");
        }
        if (role == Role.OPTIMIZER && reply != AstRole.UNARY) {
            throw new IllegalArgumentException("optimizer transaction requires UNARY reply protocol");
        }
    }

    public enum AstRole { UNARY, STREAM }
}
