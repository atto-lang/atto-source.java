package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-facing scalar field layout for an actor-owned native object.
 *
 * This is the Java/Graal equivalent of LLVM's fixed i64 actor field layout.
 * The actual scalar payload lives in an explicitly released FFM region.
 * Schema/handle wrappers remain Java-managed; it is not zero JVM GC.
 */
public final class ActorNativeI64Record implements AutoCloseable {
    public record Field(String name, long initialValue) {
        public Field {
            Objects.requireNonNull(name, "field name");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid native field name: " + name);
            }
        }
    }

    private final String typeName;
    private final ExplicitNativeI64Region region;
    private final Map<String, ExplicitNativeI64Region.Slot> fields;

    private ActorNativeI64Record(
            String typeName, ExplicitNativeI64Region region,
            Map<String, ExplicitNativeI64Region.Slot> fields) {
        this.typeName = typeName;
        this.region = region;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    static ActorNativeI64Record allocate(ActorRuntime.ActorMemorySlice memory,
                                        String typeName, List<Field> declaredFields) {
        Objects.requireNonNull(memory, "actor memory");
        Objects.requireNonNull(typeName, "type name");
        Objects.requireNonNull(declaredFields, "declared fields");
        if (!typeName.matches("[A-Za-z_][A-Za-z0-9_]*")
                || declaredFields.isEmpty() || declaredFields.size() > 1024) {
            throw new IllegalArgumentException("bounded valid actor native record required");
        }
        var schema = new LinkedHashMap<String, Long>();
        for (Field field : declaredFields) {
            Objects.requireNonNull(field, "field");
            if (schema.putIfAbsent(field.name(), field.initialValue()) != null) {
                throw new IllegalArgumentException("duplicate actor native field: " + field.name());
            }
        }

        // Validate full schema before allocating native memory. Failure is
        // atomic: no partially published field layout or native region.
        ExplicitNativeI64Region nativeRegion = memory.newExplicitNativeI64Region(schema.size());
        try {
            var slots = new LinkedHashMap<String, ExplicitNativeI64Region.Slot>();
            for (var field : schema.entrySet()) {
                slots.put(field.getKey(), nativeRegion.allocate(field.getValue()));
            }
            return new ActorNativeI64Record(typeName, nativeRegion, slots);
        } catch (RuntimeException | Error failed) {
            try {
                nativeRegion.close();
            } catch (RuntimeException closeFailure) {
                failed.addSuppressed(closeFailure);
            }
            throw failed;
        }
    }

    public String typeName() { return typeName; }
    public int fieldCount() { return fields.size(); }
    public boolean closed() { return region.closed(); }
    public boolean physicallyClosed() { return region.physicallyClosed(); }
    public int activeLoans() { return region.activeLoans(); }

    public long read(String fieldName) {
        return region.read(field(fieldName));
    }

    public ExplicitNativeI64Region.Loan borrow(String fieldName) {
        return region.borrow(field(fieldName));
    }

    public List<String> names() { return List.copyOf(fields.keySet()); }

    private ExplicitNativeI64Region.Slot field(String name) {
        Objects.requireNonNull(name, "field");
        var slot = fields.get(name);
        if (slot == null) throw new IllegalArgumentException("unknown native actor field: " + name);
        return slot;
    }

    @Override public void close() { region.close(); }
}
