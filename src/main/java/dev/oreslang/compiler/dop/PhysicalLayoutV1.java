package dev.oreslang.compiler.dop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, checked PHYSICAL layout plan between a closed scalar record and
 * backend addressing. This does not parse inverted AttoLang declarations or
 * prove that a guest compiler has emitted a field load/store.
 *
 * All segments occupy one logically actor-owned allocation; the plan itself
 * grants no ownership rights or access to memory.
 */
public final class PhysicalLayoutV1 {
    private PhysicalLayoutV1() { }
    public enum Kind { AOS, SOA, AOSOA, SOAOS }
    public record Field(String name, int bytes, int alignment) {
        public Field {
            Objects.requireNonNull(name, "field name");
            if (name.isBlank() || name.length() > 1024 || name.indexOf('\0') >= 0
                    || !powerOfTwo(bytes) || !powerOfTwo(alignment)
                    || bytes > alignment || alignment > 4096) {
                throw new IllegalArgumentException("invalid physical scalar field");
            }
        }
    }
    public record Segment(long base, long bytes, long stride, int alignment) { }
    public record Cell(int segment, long offset, long absolute) { }
    private record Locator(int segment, long offset, long rowStride) { }
    private record Shape(long stride, int alignment) { }

    public static final class Plan {
        private final Kind kind;
        private final long rows;
        private final List<Field> fields;
        private final int lanes;
        private final List<Segment> segments;
        private final Locator[] locations;
        private final long physicalBytes;

        private Plan(Kind kind, long rows, List<Field> fields, int lanes,
                     List<Segment> segments, Locator[] locations, long physicalBytes) {
            this.kind = kind;
            this.rows = rows;
            this.fields = List.copyOf(fields);
            this.lanes = lanes;
            this.segments = List.copyOf(segments);
            this.locations = locations.clone();
            this.physicalBytes = physicalBytes;
        }
        public Kind kind() { return kind; }
        public long rows() { return rows; }
        public int lanes() { return lanes; }
        public List<Field> fields() { return fields; }
        public List<Segment> segments() { return segments; }
        public long physicalBytes() { return physicalBytes; }

        /** Checked address for one scalar cell; not a raw or transferable pointer. */
        public Cell address(long row, int field) {
            if (row < 0 || row >= rows || field < 0 || field >= fields.size()) {
                throw new IndexOutOfBoundsException("physical cell outside layout bounds");
            }
            Locator loc = locations[field];
            Segment segment = segments.get(loc.segment());
            long relative = kind == Kind.AOSOA
                    ? Math.addExact(Math.addExact(
                        Math.multiplyExact(row / lanes, segment.stride()), loc.offset()),
                        Math.multiplyExact(row % lanes, loc.rowStride()))
                    : Math.addExact(Math.multiplyExact(row, loc.rowStride()), loc.offset());
            if (Math.addExact(relative, fields.get(field).bytes()) > segment.bytes()) {
                throw new IndexOutOfBoundsException("physical cell exceeds segment");
            }
            return new Cell(loc.segment(), relative, Math.addExact(segment.base(), relative));
        }
    }

    private static boolean powerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
    private static long aligned(long size, long alignment) {
        return size == 0 ? 0 : Math.multiplyExact(
                Math.addExact((size - 1) / alignment, 1), alignment);
    }

    private static Shape recordShape(List<Integer> indices, List<Field> fields,
                                     int segment, Locator[] locations) {
        long cursor = 0;
        int alignment = 1;
        for (int index : indices) {
            Field f = fields.get(index);
            cursor = aligned(cursor, f.alignment());
            locations[index] = new Locator(segment, cursor, 0);
            cursor = Math.addExact(cursor, f.bytes());
            alignment = Math.max(alignment, f.alignment());
        }
        long stride = aligned(cursor, alignment);
        for (int index : indices) {
            Locator loc = locations[index];
            locations[index] = new Locator(segment, loc.offset(), stride);
        }
        return new Shape(stride, alignment);
    }

    /**
     * Lowers a typed scalar-field record to physical segments and checked
     * cell-address expressions, without allocating or executing guest code.
     */
    public static Plan lower(Kind kind, long rows, List<Field> input,
                             int lanes, List<List<Integer>> groups, long quota) {
        Objects.requireNonNull(kind, "layout kind");
        Objects.requireNonNull(input, "physical fields");
        Objects.requireNonNull(groups, "field partition");
        if (rows < 0 || quota < 0 || input.isEmpty() || input.size() > 64
                || !powerOfTwo(lanes) || lanes > 1024
                || (kind != Kind.AOSOA && lanes != 1)) {
            throw new IllegalArgumentException("invalid physical layout dimensions");
        }
        List<Field> fields = List.copyOf(input);
        Set<String> names = new HashSet<>();
        for (Field field : fields) {
            Objects.requireNonNull(field, "physical field");
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("duplicate physical field name");
            }
        }
        if (kind != Kind.SOAOS && !groups.isEmpty()) {
            throw new IllegalArgumentException("only SoAoS accepts field groups");
        }
        Locator[] loc = new Locator[fields.size()];
        List<Segment> raw = new ArrayList<>();
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) sequence.add(i);
        switch (kind) {
            case AOS -> {
                Shape s = recordShape(sequence, fields, 0, loc);
                raw.add(new Segment(0, Math.multiplyExact(rows, s.stride()),
                        s.stride(), s.alignment()));
            }
            case SOA -> {
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    long stride = aligned(f.bytes(), f.alignment());
                    raw.add(new Segment(0, Math.multiplyExact(rows, stride), stride, f.alignment()));
                    loc[i] = new Locator(i, 0, stride);
                }
            }
            case AOSOA -> {
                long cursor = 0;
                int alignment = 1;
                for (int i = 0; i < fields.size(); i++) {
                    Field f = fields.get(i);
                    cursor = aligned(cursor, f.alignment());
                    long stride = aligned(f.bytes(), f.alignment());
                    loc[i] = new Locator(0, cursor, stride);
                    cursor = Math.addExact(cursor, Math.multiplyExact(lanes, stride));
                    alignment = Math.max(alignment, f.alignment());
                }
                long stride = aligned(cursor, alignment);
                long tiles = Math.addExact(rows / lanes, rows % lanes == 0 ? 0 : 1);
                raw.add(new Segment(0, Math.multiplyExact(tiles, stride), stride, alignment));
            }
            case SOAOS -> {
                if (groups.isEmpty()) {
                    throw new IllegalArgumentException("SoAoS requires a field partition");
                }
                Set<Integer> seen = new HashSet<>();
                for (List<Integer> group : groups) {
                    if (group == null || group.isEmpty()) {
                        throw new IllegalArgumentException("empty SoAoS field group");
                    }
                    for (Integer index : group) {
                        if (index == null || index < 0 || index >= fields.size()
                                || !seen.add(index)) {
                            throw new IllegalArgumentException("invalid SoAoS field partition");
                        }
                    }
                    Shape s = recordShape(group, fields, raw.size(), loc);
                    raw.add(new Segment(0, Math.multiplyExact(rows, s.stride()),
                            s.stride(), s.alignment()));
                }
                if (seen.size() != fields.size()) {
                    throw new IllegalArgumentException("incomplete SoAoS field partition");
                }
            }
        }
        long cursor = 0;
        List<Segment> located = new ArrayList<>(raw.size());
        for (Segment segment : raw) {
            cursor = aligned(cursor, segment.alignment());
            long next = Math.addExact(cursor, segment.bytes());
            if (next > quota) throw new IllegalArgumentException("physical allocation quota");
            located.add(new Segment(cursor, segment.bytes(), segment.stride(), segment.alignment()));
            cursor = next;
        }
        return new Plan(kind, rows, fields, lanes, located, loc, cursor);
    }
}
