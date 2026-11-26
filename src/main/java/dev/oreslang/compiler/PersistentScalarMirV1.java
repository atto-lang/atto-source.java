package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.DurableActorStore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Verified source-AST -> restricted persistent actor scalar MIR v1.
 *
 * This deliberately admits only a single owned i64 field and two fixed-shape
 * methods: increment(int amount) and get_count(). It is not general guest
 * actor execution and it does NOT relax the ordinary TypeChecker gate.
 * Unrecognized effects, calls, unsafe references, suspension and loops cannot
 * enter this runtime through a user-defined method body.
 */
public final class PersistentScalarMirV1 {
    private PersistentScalarMirV1() { }

    public record Plan(String actorName, String stateField, long initialValue,
                       String codeGeneration) {
        public Plan {
            Objects.requireNonNull(actorName, "actor name");
            Objects.requireNonNull(stateField, "state field");
            Objects.requireNonNull(codeGeneration, "code generation");
            if (actorName.isBlank() || stateField.isBlank() || codeGeneration.isBlank())
                throw new IllegalArgumentException("invalid persistent scalar plan");
        }

        /** Trusted supervisor provisions only if this logical actor is absent. */
        public DurableActorStore.Snapshot initial_snapshot() {
            return snapshot(0, initialValue);
        }

        /** Verified encoding: one signed 64-bit integer, network byte order. */
        public DurableActorStore.Snapshot snapshot(long version, long value) {
            return new DurableActorStore.Snapshot(
                    0, 1, codeGeneration, version, encode(value));
        }

        public long decode(DurableActorStore.Snapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            if (snapshot.formatVersion() != 0 || snapshot.stateSchemaVersion() != 1
                    || !codeGeneration.equals(snapshot.codeGeneration()))
                throw new IllegalStateException("PERSISTENT_ACTOR_SCHEMA_MISMATCH");
            return decode(snapshot.encodedState());
        }

        public byte[] encode(long value) {
            return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putLong(value).array();
        }

        public long decode(byte[] data) {
            if (data == null || data.length != Long.BYTES)
                throw new IllegalStateException("PERSISTENT_ACTOR_INVALID_I64_CODEC");
            return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getLong();
        }

        /** AOT metadata derived from the verified source AST, not host reflection. */
        public DurableScalarSchemaV1.Descriptor state_schema() {
            return DurableScalarSchemaV1.forState(actorName, stateField);
        }

        public DurableScalarSchemaV1.Descriptor increment_schema() {
            return DurableScalarSchemaV1.forIncrement(actorName);
        }

        /** All newly admitted increment commands carry a versioned typed header. */
        public byte[] encode_increment_message(long delta) {
            return DurableScalarSchemaV1.encode(increment_schema(), delta);
        }

        /**
         * The earlier pre-versioned eight-byte scalar inbox format is pinned as
         * a single explicit v0 -> v1 compatibility path. It is NOT a generic
         * best-effort fallback for unknown versions or other actor types.
         */
        public long decode_increment_message(byte[] originalWire) {
            if (originalWire != null && originalWire.length == Long.BYTES)
                return decode(originalWire);
            return DurableScalarSchemaV1.decode(increment_schema(), originalWire);
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("PERSISTENT_SCALAR_MIR_UNSUPPORTED: " + reason);
    }

    public static Plan lower(String source) {
        Objects.requireNonNull(source, "source");
        if (source.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw invalid("source budget exceeded");
        return lower(Parser.parse(source));
    }

    public static Plan lower(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        if (!program.imports().isEmpty() || program.modules().size() != 1)
            throw invalid("imports and multi-module source not yet proven");
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!module.name().equals(Parser.ROOT_MODULE)
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()
                || module.declarations().size() != 1)
            throw invalid("only one closed root persistent actor is supported");
        if (!(module.declarations().getFirst() instanceof Ast.PersistentActorDecl actor))
            throw invalid("expected dedicated persistent actor AST");
        if (actor.visibility() != Ast.Visibility.PRIVATE
                || actor.name().length() > 128
                || actor.stateFields().size() != 1 || actor.methods().size() != 2)
            throw invalid("private actor, one field and two methods required");

        Ast.ProtectedMethod writer = null;
        Ast.ProtectedMethod reader = null;
        for (Ast.ProtectedMethod method : actor.methods()) {
            if (method.name().equals("increment") && writer == null) writer = method;
            else if (method.name().equals("get_count") && reader == null) reader = method;
            else throw invalid("only unique increment/get_count methods allowed");
        }
        if (writer == null || reader == null)
            throw invalid("both increment and get_count methods required");

        // Reuse the existing production AST => typed acquire/write/release proof,
        // not a new pattern matcher that could accidentally loosen write rules.
        var converted = new Ast.StateContainerDecl(actor.name(), actor.visibility(),
                actor.stateFields(), List.of(writer));
        var wrapped = new Ast.Program(program.namespace(), program.imports(),
                List.of(new Ast.ModuleDecl(module.name(), module.annotations(),
                        module.contracts(), List.of(converted))));
        ProtectedScalarMirV1.Plan checkedWriter;
        try {
            checkedWriter = ProtectedScalarMirV1.lower(wrapped);
        } catch (IllegalArgumentException failure) {
            throw invalid("write method rejected by protected-state MIR: " + failure.getMessage());
        }

        if (!reader.parameters().isEmpty() || !isInt(reader.returnType()))
            throw invalid("get_count must have no arguments and return int");
        checkReader(reader, checkedWriter.stateField());
        String generation = fingerprint(actor, checkedWriter);
        return new Plan(actor.name(), checkedWriter.stateField(),
                checkedWriter.initialValue(), generation);
    }

    private static boolean isInt(Ast.TypeRef type) {
        return type.name().equals("int") && type.arguments().isEmpty()
                && !type.inferArguments();
    }

    private record TokenCursor(List<String> texts, List<String> kinds) {
        private void require(String[] expectedText, String[] expectedKind) {
            if (texts.size() != expectedText.length || kinds.size() != expectedKind.length)
                throw invalid("unexpected get_count body length");
            for (int i = 0; i < expectedText.length; i++) {
                if (!texts.get(i).equals(expectedText[i])
                        || !kinds.get(i).equals(expectedKind[i]))
                    throw invalid("unverified get_count token at " + i);
            }
        }
    }

    private static void checkReader(Ast.ProtectedMethod reader, String field) {
        List<String> tokens = reader.bodyTokens();
        List<String> kinds = reader.bodyTokenKinds();
        if (tokens.size() != kinds.size() || tokens.size() > 36)
            throw invalid("invalid get_count token provenance/budget");
        String[] bare = {"return", "handle_state", "(", "critical", "-", "read", "|",
                "s", "|", "->", "s", ".", field, ")", ";"};
        String[] bareKinds = {"RETURN", "IDENT", "LPAREN", "IDENT", "MINUS", "IDENT",
                "PIPE", "IDENT", "PIPE", "ARROW", "IDENT", "DOT", "IDENT",
                "RPAREN", "SEMICOLON"};
        if (!tokens.isEmpty() && tokens.size() > 2 && tokens.get(1).equals("self")) {
            String[] prefixed = {"return", "self", ".", "handle_state", "(", "critical",
                    "-", "read", "|", "s", "|", "->", "s", ".", field, ")", ";"};
            String[] prefixedKinds = {"RETURN", "SELF", "DOT", "IDENT", "LPAREN",
                    "IDENT", "MINUS", "IDENT", "PIPE", "IDENT", "PIPE", "ARROW",
                    "IDENT", "DOT", "IDENT", "RPAREN", "SEMICOLON"};
            new TokenCursor(tokens, kinds).require(prefixed, prefixedKinds);
        } else {
            new TokenCursor(tokens, kinds).require(bare, bareKinds);
        }
    }

    private static String fingerprint(Ast.PersistentActorDecl actor,
                                      ProtectedScalarMirV1.Plan write) {
        // Hash the validated source tokens AND their provenance: a source edit
        // requires explicit code-generation migration on the same logical key.
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            update(hash, "persistent-scalar-mir-v1");
            update(hash, actor.name());
            update(hash, write.stateField());
            update(hash, Long.toString(write.initialValue()));
            for (Ast.ProtectedMethod method : actor.methods()) {
                update(hash, method.name());
                for (int i = 0; i < method.bodyTokens().size(); i++) {
                    update(hash, method.bodyTokenKinds().get(i));
                    update(hash, method.bodyTokens().get(i));
                }
            }
            return "persistent-i64-v1:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest hash, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
}
