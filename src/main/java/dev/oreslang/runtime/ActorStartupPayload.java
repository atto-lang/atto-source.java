package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Scalar-only host prototype. A real compiler must mint the nominal schema
 * and prove startup transfer and guest allocation; no user-visible authority. */
final class ActorStartupPayload {
    enum Kind { INT, BOOL, STRING }
    record Field(String name, Kind kind) {
        Field {
            if (name == null || name.isBlank() || name.length() > 128)
                throw new IllegalArgumentException("E_SPAWN_INIT_SCHEMA");
            Objects.requireNonNull(kind);
        }
    }
    record Argument(String name, Object value) { }
    static final class Schema {
        private final List<Field> fields;
        Schema(List<Field> fields) {
            this.fields = List.copyOf(fields);
            if (this.fields.size() > 64) throw new IllegalArgumentException("E_SPAWN_INIT_SCHEMA");
            var names = new HashSet<String>();
            for (Field field : this.fields)
                if (!names.add(field.name())) throw new IllegalArgumentException("E_SPAWN_INIT_SCHEMA");
        }
        ActorStartupPayload bind(List<Argument> arguments, long byteLimit) {
            if (byteLimit < 0 || arguments.size() != fields.size())
                throw new IllegalArgumentException("E_SPAWN_INIT_FIELDS");
            var incoming = new HashMap<String, Object>();
            for (Argument argument : arguments) {
                if (argument.name() == null || argument.value() == null ||
                        incoming.putIfAbsent(argument.name(), argument.value()) != null)
                    throw new IllegalArgumentException("E_SPAWN_INIT_FIELDS");
            }
            var values = new ArrayList<Object>(fields.size());
            long bytes = 0;
            for (Field field : fields) {
                Object value = incoming.get(field.name());
                long size = switch (field.kind()) {
                    case INT -> value instanceof Long ? 8 : -1;
                    case BOOL -> value instanceof Boolean ? 1 : -1;
                    case STRING -> value instanceof String s ? utf8Bytes(s) : -1;
                };
                if (size < 0) throw new IllegalArgumentException("E_SPAWN_INIT_TYPE");
                // Semantic scalar byte budget, not a physical JVM object-size claim.
                if (size > byteLimit - bytes) throw new IllegalArgumentException("E_SPAWN_INIT_BYTES");
                bytes += size;
                values.add(value);
            }
            return new ActorStartupPayload(this, List.copyOf(values), bytes);
        }
    }
    private final Schema schema;
    private final List<Object> values;
    private final long bytes;
    private ActorStartupPayload(Schema schema, List<Object> values, long bytes) {
        this.schema = schema; this.values = values; this.bytes = bytes;
    }
    boolean matches(Schema expected) { return schema == expected; }
    long bytes() { return bytes; }
    Object get(int index) { return values.get(index); }

    private static long utf8Bytes(String s) {
        long bytes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
                    throw new IllegalArgumentException("E_SPAWN_INIT_UTF8");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("E_SPAWN_INIT_UTF8");
            } else bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
        }
        return bytes;
    }
}
