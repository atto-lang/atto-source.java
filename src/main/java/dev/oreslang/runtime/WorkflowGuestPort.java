package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

/**
 * Converts exclusively scalar, owned guest values into the host registry's
 * immutable workflow protocol. No Java object, JNI handle, path or policy
 * crosses the guest boundary. This is NOT a public guest Java class import.
 *
 * Wire request: 15 positions:
 * request_id, operation, tenant, run, revision, generation, epoch, version,
 * step, message_id, effect_key, digest, outcome, receipt, capability.
 *
 * Reply: [request_id, status, epoch, version, prepared, in_doubt, committed].
 */
final class WorkflowGuestPort {
    private WorkflowGuestPort() { }

    private static String string(List<?> v, int i, int max) {
        Object value = v.get(i);
        if (!(value instanceof String text) || text.length() > max) {
            throw new IllegalArgumentException("workflow request field " + i + " must be a bounded String");
        }
        return text;
    }

    private static long integer(List<?> v, int i) {
        Object value = v.get(i);
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof Long number) return number;
        throw new IllegalArgumentException("workflow request field " + i + " must be a signed int");
    }

    static WorkflowHostServiceRegistry.Request decode(Object payload) {
        if (!(payload instanceof List<?> values) || values.size() != 15) {
            throw new IllegalArgumentException("rt workflow_request expects a 15-element scalar list");
        }
        WorkflowHostServiceRegistry.Operation action;
        try {
            action = WorkflowHostServiceRegistry.Operation.valueOf(string(values, 1, 24).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unsupported workflow request operation", failure);
        }
        return new WorkflowHostServiceRegistry.Request(
                string(values, 0, 96), action,
                string(values, 2, 96), string(values, 3, 96),
                string(values, 4, 40), integer(values, 5),
                integer(values, 6), integer(values, 7),
                string(values, 8, 160), string(values, 9, 160),
                string(values, 10, 160), string(values, 11, 160),
                string(values, 12, 160), string(values, 13, 160),
                string(values, 14, 4096));
    }

    static OresFuture<List<Object>> submit(WorkflowHostServiceRegistry registry, Object payload) {
        WorkflowHostServiceRegistry.Request request = decode(payload);
        CompletionStage<List<Object>> stage = registry.submit(request).thenApply(reply -> {
            // A detached immutable scalar result. The host's Service,
            // Throwable, CompletionStage and JNI handles are never returned.
            ArrayList<Object> scalar = new ArrayList<>(7);
            scalar.add(reply.requestId());
            scalar.add(reply.status().name().toLowerCase(Locale.ROOT));
            scalar.add(reply.epoch());
            scalar.add(reply.version());
            scalar.add(reply.prepared());
            scalar.add(reply.inDoubt());
            scalar.add(reply.committed());
            return List.copyOf(scalar);
        });
        return OresFuture.from(stage);
    }
}
