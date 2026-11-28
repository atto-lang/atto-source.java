package dev.oreslang.runtime;

import java.util.*;

/** Host-only scalar startup schema. No guest publication or ownership transfer. */
final class ActorStartupSchema {
    enum Kind { INT64, BOOL }
    record Field(String name, Kind kind) {
        Field { Objects.requireNonNull(name); Objects.requireNonNull(kind); if (name.isEmpty()) throw invalid(); }
    }
    record Supplied(String name, Object value) { }
    private final List<Field> fields;
    private final Map<String, Kind> byName;

    ActorStartupSchema(List<Field> fields) {
        this.fields = List.copyOf(fields);
        if (this.fields.size() > 256) throw invalid();
        var index = new HashMap<String, Kind>();
        for (var field : this.fields) if (index.putIfAbsent(field.name(), field.kind()) != null) throw invalid();
        this.byName = Map.copyOf(index);
    }
    Payload validate(List<Supplied> supplied) {
        var values = new HashMap<String, Object>();
        if (supplied.size() != fields.size()) throw invalid();
        for (var entry : supplied) {
            if (entry == null || entry.name() == null) throw invalid();
            Kind kind = byName.get(entry.name());
            Object value = entry.value();
            if (kind == null || !(kind == Kind.INT64 ? value instanceof Long : value instanceof Boolean)
                    || values.putIfAbsent(entry.name(), value) != null) throw invalid();
        }
        // Values were already evaluated in caller order; layout uses declaration order.
        return new Payload(this, fields.stream().map(f -> values.get(f.name())).toList());
    }
    List<Object> valuesFor(Payload payload) {
        if (payload == null || payload.owner != this) throw invalid();
        return payload.values;
    }
    static final class Payload {
        private final ActorStartupSchema owner;
        private final List<Object> values;
        private Payload(ActorStartupSchema owner, List<Object> values) {
            this.owner = owner; this.values = List.copyOf(values);
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("E_SPAWN_INIT_SCHEMA"); }
}
