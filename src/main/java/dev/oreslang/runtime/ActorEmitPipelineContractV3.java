package dev.oreslang.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * AttoLang source-level actor pipe v3 semantic admission, shared by the
 * Java/LLVM/.NET compiler frontends. This is NOT a runnable guest pipeline.
 *
 * The compiler must supply a fresh actor instance identity, the declared
 * ActorMail<T> receive type and the INFERRED type of every emit(expr) found in
 * the receive body (including conditional and loop arms). Ordinary actors
 * with a verified receive handler need no legacy readable/transform role.
 *
 * No actor instance, scheduler capability, mutable heap or mailbox is
 * allocated by this pure contract. Executable source remains gated until
 * compiler-backed routing, owned arenas and supervisor fencing exist.
 */
public final class ActorEmitPipelineContractV3 {
    private ActorEmitPipelineContractV3() { }

    public static final int MAX_STAGES = 16;

    public record Stage(
            String instanceId,
            String actorType,
            String receiveType,
            List<String> emitTypes,
            boolean supervisor) {
        public Stage {
            requireIdentity(instanceId, "instance");
            requireIdentity(actorType, "actor type");
            requireScalar(receiveType);
            Objects.requireNonNull(emitTypes, "emitted types");
            emitTypes = List.copyOf(emitTypes);
            for (String type : emitTypes) requireScalar(type);
        }
    }

    public record Link(String source, String target, String payloadType) { }

    private static IllegalArgumentException reject(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private static void requireIdentity(String value, String field) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0)
            throw reject("E_ACTOR_PIPE_IDENTITY", field + " must be a nonempty, NUL-free identifier");
        // An N-code-unit UTF-16 string needs at least N UTF-8 bytes when valid.
        // Reject impossible identities before the encoder allocates a buffer
        // proportional to potentially untrusted compiler input.
        if (value.length() > 256)
            throw reject("E_ACTOR_PIPE_IDENTITY", field + " exceeds 256 UTF-8 bytes");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            if (bytes.remaining() > 256)
                throw reject("E_ACTOR_PIPE_IDENTITY", field + " exceeds 256 UTF-8 bytes");
        } catch (CharacterCodingException error) {
            throw reject("E_ACTOR_PIPE_IDENTITY", field + " must contain valid UTF-8");
        }
    }

    private static void requireScalar(String type) {
        if (!"int".equals(type) && !"bool".equals(type))
            throw reject("E_ACTOR_PIPE_PAYLOAD_DOMAIN",
                    "v3 ActorMail/emit requires nominal int or bool");
    }

    /**
     * Check a source-ordered linear pipeline. A terminal sink must not emit:
     * a runtime-only failure after the last stage is not an acceptable type
     * check. Duplicate nominal actor types are allowed, duplicate instances
     * are not. Emitting values of different types in branches is forbidden.
     */
    public static List<Link> validate(List<Stage> stages) {
        Objects.requireNonNull(stages, "stages");
        if (stages.size() < 2 || stages.size() > MAX_STAGES)
            throw reject("E_ACTOR_PIPE_LIMIT", "pipeline requires 2..16 distinct actor instances");

        var seen = new HashSet<String>();
        var links = new ArrayList<Link>(stages.size() - 1);
        String previousOutput = null;
        String previousIdentity = null;
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = Objects.requireNonNull(stages.get(index), "stage");
            if (!seen.add(stage.instanceId()))
                throw reject("E_ACTOR_PIPE_DUPLICATE_INSTANCE",
                        "actor instance was attached more than once");
            if (stage.supervisor())
                throw reject("E_ACTOR_PIPE_SUPERVISOR", "CONTROL supervisor cannot be a DATA stage");

            String output = null;
            for (String type : stage.emitTypes()) {
                if (output != null && !output.equals(type))
                    throw reject("E_ACTOR_PIPE_EMIT_TYPE", stage.actorType()
                            + " has incompatible emitted value types " + output + " and " + type);
                output = type;
            }
            if (index > 0) {
                if (!previousOutput.equals(stage.receiveType()))
                    throw reject("E_ACTOR_PIPE_EMIT_TYPE",
                            previousIdentity + " emits " + previousOutput
                            + " but " + stage.instanceId() + " receives " + stage.receiveType());
                links.add(new Link(previousIdentity, stage.instanceId(), previousOutput));
            }
            if (index + 1 == stages.size()) {
                if (output != null)
                    throw reject("E_ACTOR_PIPE_SINK_EMIT",
                            "terminal pipeline sink cannot emit without a downstream actor");
            } else if (output == null) {
                throw reject("E_ACTOR_PIPE_STAGE_NO_EMIT",
                        "nonterminal pipeline stage must emit a typed value");
            }
            previousOutput = output;
            previousIdentity = stage.instanceId();
        }
        return List.copyOf(links);
    }
}
