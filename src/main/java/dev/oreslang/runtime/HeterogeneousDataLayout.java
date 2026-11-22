package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Checked byte geometry for mixed-width POD fields in the four DOP layouts:
 * AoS, SoA, AoSoA and grouped SoAoS.
 *
 * <p>Java mirror of the native {@code oreslang::dop::HeterogeneousPlan}
 * ({@code DopHeterogeneousLayout.hpp} in oreslang-source.llvm). Offsets, strides,
 * segment bases, payload bytes and physical bytes must match it exactly; the
 * shared golden case is pinned in {@code HeterogeneousDataLayoutTest}.</p>
 *
 * <p>SoAoS stores one compact AoS sub-record per field group, each group in its
 * own segment; unlike AoSoA it never uses lane tiles. All segments live in ONE
 * owner allocation, so {@link Plan#physicalBytes()} charges the alignment holes
 * between segment bases and is what quotas are checked against.</p>
 *
 * <p>This is a host-side descriptor, not an allocator, guest type or lowering of
 * {@code define inverted class}. All arithmetic is checked signed 64-bit
 * (the Java ABI limit) and every malformed shape fails before allocation.</p>
 */
public final class HeterogeneousDataLayout {
    private HeterogeneousDataLayout() {}

    public static final int MAX_FIELDS = 1024;
    public static final int MAX_LANES = 1024;
    public static final int MAX_ALIGNMENT = 4096;

    public enum Kind { AOS, SOA, AOSOA, SOAOS }

    public record Field(int bytes, int alignment) {
        public Field {
            if (!powerOfTwo(bytes) || !powerOfTwo(alignment)
                    || bytes > alignment || alignment > MAX_ALIGNMENT) {
                throw new IllegalArgumentException("invalid heterogeneous scalar width/alignment");
            }
        }
    }

    public record Segment(int alignment, long bytes, long stride, long base) {}

    public record CellAddress(int segment, long byteOffset) {}

    private record Locator(int segment, long offset, long rowStride) {}

    private record RecordShape(int alignment, long stride, long[] offsets) {}

    public static final class Plan {
        private final Kind kind;
        private final long rows;
        private final List<Field> fields;
        private final int lanes;
        private final List<Segment> segments;
        private final Locator[] locators;
        private final long totalBytes;
        private final long physicalBytes;

        public Plan(Kind kind, long rows, List<Field> fields) {
            this(kind, rows, fields, 1, List.of(), Long.MAX_VALUE, Long.MAX_VALUE);
        }

        public Plan(Kind kind, long rows, List<Field> fields, int lanes,
                    List<List<Integer>> groups, long maxSegmentBytes, long maxTotalBytes) {
            this.kind = Objects.requireNonNull(kind, "layout kind");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(groups, "groups");
            if (rows < 0) throw new IllegalArgumentException("negative row count");
            if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
                throw new IllegalArgumentException("heterogeneous layout requires 1.." + MAX_FIELDS + " fields");
            }
            this.fields = List.copyOf(fields);
            if (kind != Kind.AOSOA && lanes != 1) {
                throw new IllegalArgumentException("only AoSoA accepts multiple lanes");
            }
            if (!powerOfTwo(lanes) || lanes > MAX_LANES) {
                throw new IllegalArgumentException("invalid AoSoA tile width");
            }
            if (kind != Kind.SOAOS && !groups.isEmpty()) {
                throw new IllegalArgumentException("field groups are exclusive to SoAoS");
            }
            if (kind == Kind.SOAOS && groups.isEmpty()) {
                throw new IllegalArgumentException("SoAoS requires an explicit nonempty partition");
            }
            this.rows = rows;
            this.lanes = lanes;
            this.locators = new Locator[this.fields.size()];
            List<Segment> unplaced = new ArrayList<>();

            switch (kind) {
                case AOS -> {
                    RecordShape row = recordShape(sequence(this.fields.size()));
                    unplaced.add(new Segment(row.alignment(), Math.multiplyExact(rows, row.stride()), row.stride(), 0));
                    for (int i = 0; i < locators.length; i++) {
                        locators[i] = new Locator(0, row.offsets()[i], row.stride());
                    }
                }
                case SOA -> {
                    for (int i = 0; i < locators.length; i++) {
                        Field field = this.fields.get(i);
                        long stride = align(field.bytes(), field.alignment());
                        unplaced.add(new Segment(field.alignment(), Math.multiplyExact(rows, stride), stride, 0));
                        locators[i] = new Locator(i, 0, stride);
                    }
                }
                case AOSOA -> {
                    long cursor = 0;
                    int alignment = 1;
                    for (int i = 0; i < locators.length; i++) {
                        Field field = this.fields.get(i);
                        cursor = align(cursor, field.alignment());
                        long stride = align(field.bytes(), field.alignment());
                        locators[i] = new Locator(0, cursor, stride);
                        cursor = Math.addExact(cursor, Math.multiplyExact(stride, (long) lanes));
                        alignment = Math.max(alignment, field.alignment());
                    }
                    long tileStride = align(cursor, alignment);
                    long tiles = rows / lanes + (rows % lanes != 0 ? 1 : 0);
                    unplaced.add(new Segment(alignment, Math.multiplyExact(tiles, tileStride), tileStride, 0));
                }
                case SOAOS -> {
                    boolean[] seen = new boolean[this.fields.size()];
                    int accounted = 0;
                    for (List<Integer> group : groups) {
                        Objects.requireNonNull(group, "SoAoS field group");
                        if (group.isEmpty()) throw new IllegalArgumentException("SoAoS field group is empty");
                        int[] indices = new int[group.size()];
                        for (int i = 0; i < indices.length; i++) {
                            Integer index = Objects.requireNonNull(group.get(i), "SoAoS field index");
                            if (index < 0 || index >= seen.length || seen[index]) {
                                throw new IllegalArgumentException("SoAoS field groups are not a partition");
                            }
                            seen[index] = true;
                            indices[i] = index;
                            accounted++;
                        }
                        RecordShape record = recordShape(indices);
                        int segment = unplaced.size();
                        unplaced.add(new Segment(record.alignment(),
                                Math.multiplyExact(rows, record.stride()), record.stride(), 0));
                        for (int i = 0; i < indices.length; i++) {
                            locators[indices[i]] = new Locator(segment, record.offsets()[i], record.stride());
                        }
                    }
                    if (accounted != seen.length) {
                        throw new IllegalArgumentException("SoAoS field groups do not cover all fields");
                    }
                }
            }

            long payload = 0;
            long physical = 0;
            List<Segment> placed = new ArrayList<>(unplaced.size());
            for (Segment segment : unplaced) {
                if (segment.bytes() > maxSegmentBytes) {
                    throw new IllegalStateException("heterogeneous segment exceeds physical quota");
                }
                physical = align(physical, segment.alignment());
                placed.add(new Segment(segment.alignment(), segment.bytes(), segment.stride(), physical));
                physical = Math.addExact(physical, segment.bytes());
                payload = Math.addExact(payload, segment.bytes());
                if (physical > maxTotalBytes) {
                    throw new IllegalStateException("heterogeneous layout exceeds owner quota");
                }
            }
            this.segments = List.copyOf(placed);
            this.totalBytes = payload;
            this.physicalBytes = physical;
        }

        public Kind kind() { return kind; }
        public long rows() { return rows; }
        public int fieldCount() { return fields.size(); }
        public int lanes() { return lanes; }
        public List<Segment> segments() { return segments; }
        /** Sum of segment payload bytes, excluding inter-segment alignment holes. */
        public long totalBytes() { return totalBytes; }
        /** Extent of the single owner allocation, including alignment holes. */
        public long physicalBytes() { return physicalBytes; }

        public CellAddress address(long row, int field) {
            if (row < 0 || row >= rows || field < 0 || field >= locators.length) {
                throw new IndexOutOfBoundsException("heterogeneous row/field out of bounds");
            }
            Locator loc = locators[field];
            if (kind == Kind.AOSOA) {
                long tile = row / lanes;
                long lane = row % lanes;
                return new CellAddress(loc.segment(), Math.addExact(Math.addExact(
                        Math.multiplyExact(tile, segments.get(0).stride()), loc.offset()),
                        Math.multiplyExact(lane, loc.rowStride())));
            }
            return new CellAddress(loc.segment(),
                    Math.addExact(Math.multiplyExact(row, loc.rowStride()), loc.offset()));
        }

        public long absoluteAddress(long row, int field) {
            CellAddress cell = address(row, field);
            return Math.addExact(segments.get(cell.segment()).base(), cell.byteOffset());
        }

        private RecordShape recordShape(int[] indices) {
            int alignment = 1;
            long stride = 0;
            long[] offsets = new long[indices.length];
            for (int i = 0; i < indices.length; i++) {
                Field f = fields.get(indices[i]);
                stride = align(stride, f.alignment());
                offsets[i] = stride;
                stride = Math.addExact(stride, f.bytes());
                alignment = Math.max(alignment, f.alignment());
            }
            return new RecordShape(alignment, align(stride, alignment), offsets);
        }
    }

    private static int[] sequence(int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) values[i] = i;
        return values;
    }

    private static boolean powerOfTwo(long v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    private static long align(long bytes, long alignment) {
        if (bytes == 0) return 0;
        return Math.multiplyExact(((bytes - 1) / alignment) + 1, alignment);
    }
}
