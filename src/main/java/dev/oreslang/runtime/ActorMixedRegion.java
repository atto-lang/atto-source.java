package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Native, actor-owned heterogeneous ECS/SoA/SoAoS payload storage. This is a
 * host-runtime substrate, not generated Oreslang inverted-class lowering.
 * Mutable guest bytes live exclusively in a native ActorNativeArena.Region;
 * immutable schema/geometry metadata is managed Java state.
 *
 * <p>Ref32 denotes an encoded unsigned word, NOT a pointer/capability. No
 * dereference API exists here. Row generations and compiler borrow proofs
 * remain future compiler work.</p>
 */
public final class ActorMixedRegion implements AutoCloseable {
    public enum Kind {
        U32(4), F64(8), BOOL(1), REF32(4);
        final int width;
        Kind(int width) { this.width = width; }
    }
    public enum Layout { AOS, SOA, AOSOA, SOAOS }
    public record Field(String name, Kind kind) { }
    public record Segment(long base, long bytes, long stride, int alignment) { }
    public record Address(int segment, int absoluteByteOffset, int segmentByteOffset, Kind kind) { }
    private record Location(int segment, long offset, long stride) { }
    private record Shape(long stride, int alignment, long[] offsets) { }

    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private final ActorNativeArena arena;
    private final ActorNativeArena.Region memory;
    private final List<Field> fields;
    private final Map<String, Integer> byName;
    private final List<Segment> segments;
    private final Location[] locations;
    private final Layout layout;
    private final int rows;
    private final int lanes;
    private final int physicalBytes;
    private final long payloadBytes;

    public ActorMixedRegion(ActorNativeArena arena, List<Field> declarations,
            int rows, Layout layout, int lanes, List<List<String>> groups, int maxBytes) {
        this.arena = Objects.requireNonNull(arena);
        Objects.requireNonNull(declarations);
        this.layout = Objects.requireNonNull(layout);
        if (rows <= 0 || declarations.isEmpty() || declarations.size() > 1024) {
            throw new IllegalArgumentException("mixed field count and rows must be bounded positive");
        }
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("mixed physical limit must be 1..16MiB");
        }
        if (layout == Layout.AOSOA) {
            if (lanes < 1 || lanes > 1024 || (lanes & (lanes - 1)) != 0) {
                throw new IllegalArgumentException("AoSoA tile width must be a power of two");
            }
        } else if (lanes != 1) {
            throw new IllegalArgumentException("lanes only apply to AoSoA");
        }
        if (layout != Layout.SOAOS && groups != null) {
            throw new IllegalArgumentException("field groups only apply to SoAoS");
        }
        this.rows = rows;
        this.lanes = lanes;
        this.fields = List.copyOf(declarations);
        HashMap<String, Integer> lookup = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = Objects.requireNonNull(fields.get(i), "field");
            Objects.requireNonNull(f.kind(), "field kind");
            if (!f.name().matches("[$A-Za-z_][$A-Za-z_0-9]*")
                    || lookup.putIfAbsent(f.name(), i) != null) {
                throw new IllegalArgumentException("invalid or duplicate native mixed field");
            }
        }
        this.byName = Map.copyOf(lookup);
        this.locations = new Location[fields.size()];
        ArrayList<Segment> computed = new ArrayList<>();
        if (layout == Layout.AOS) {
            int[] indices = new int[fields.size()];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            Shape shape = shape(indices);
            computed.add(new Segment(0, Math.multiplyExact(shape.stride, rows),
                    shape.stride, shape.alignment));
            for (int i = 0; i < indices.length; i++) {
                locations[i] = new Location(0, shape.offsets[i], shape.stride);
            }
        } else if (layout == Layout.SOA) {
            for (int i = 0; i < fields.size(); i++) {
                int width = fields.get(i).kind().width;
                computed.add(new Segment(0, Math.multiplyExact((long) rows, width),
                        width, width));
                locations[i] = new Location(i, 0, width);
            }
        } else if (layout == Layout.AOSOA) {
            long cursor = 0;
            int maxAlign = 1;
            for (int i = 0; i < fields.size(); i++) {
                int width = fields.get(i).kind().width;
                cursor = align(cursor, width);
                locations[i] = new Location(0, cursor, width);
                cursor = Math.addExact(cursor, Math.multiplyExact((long) width, lanes));
                maxAlign = Math.max(maxAlign, width);
            }
            long stride = align(cursor, maxAlign);
            long tiles = Math.addExact(rows / lanes, rows % lanes == 0 ? 0 : 1);
            computed.add(new Segment(0, Math.multiplyExact(tiles, stride), stride, maxAlign));
        } else {
            if (groups == null || groups.isEmpty() || groups.size() > fields.size()) {
                throw new IllegalArgumentException("SoAoS requires bounded explicit field groups");
            }
            boolean[] seen = new boolean[fields.size()];
            int accounted = 0;
            for (List<String> group : groups) {
                if (group == null || group.isEmpty()) {
                    throw new IllegalArgumentException("SoAoS group must not be empty");
                }
                int[] members = new int[group.size()];
                for (int i = 0; i < group.size(); i++) {
                    Integer index = byName.get(group.get(i));
                    if (index == null || seen[index]) {
                        throw new IllegalArgumentException("SoAoS groups must partition fields");
                    }
                    seen[index] = true;
                    members[i] = index;
                    accounted++;
                }
                Shape shape = shape(members);
                int segment = computed.size();
                computed.add(new Segment(0, Math.multiplyExact(shape.stride, rows),
                        shape.stride, shape.alignment));
                for (int i = 0; i < members.length; i++) {
                    locations[members[i]] = new Location(segment, shape.offsets[i], shape.stride);
                }
            }
            if (accounted != fields.size()) {
                throw new IllegalArgumentException("SoAoS group partition incomplete");
            }
        }

        long total = 0, payload = 0;
        ArrayList<Segment> placed = new ArrayList<>(computed.size());
        for (Segment segment : computed) {
            total = align(total, segment.alignment);
            placed.add(new Segment(total, segment.bytes, segment.stride,
                    segment.alignment));
            total = Math.addExact(total, segment.bytes);
            payload = Math.addExact(payload, segment.bytes);
            if (total > maxBytes) {
                throw new IllegalArgumentException("physical native actor store quota exceeded");
            }
        }
        this.physicalBytes = Math.toIntExact(total);
        this.payloadBytes = payload;
        this.segments = List.copyOf(placed);
        // No guest-storage bytes are committed before every geometry and quota
        // check passes; native release remains owned by the same actor arena.
        this.memory = arena.region(physicalBytes);
    }

    public ActorMixedRegion(ActorNativeArena arena, List<Field> fields,
            int rows, Layout layout, int lanes, List<List<String>> groups) {
        this(arena, fields, rows, layout, lanes, groups, MAX_BYTES);
    }

    /**
     * Production entry point: take ownership from the currently admitted
     * physical actor turn, never from a carrier-thread identity.
     */
    public static ActorMixedRegion forCurrentActor(
            List<Field> fields, int rows, Layout layout, int lanes,
            List<List<String>> groups) {
        ActorNativeArena owner = ActorRuntime.currentNativeArena();
        if (owner == null) throw new SecurityException(
                "mixed actor storage requires an executing actor turn");
        return new ActorMixedRegion(owner, fields, rows, layout, lanes, groups);
    }

    private static long align(long value, int alignment) {
        return Math.multiplyExact(Math.addExact(value, alignment - 1L) / alignment,
                (long) alignment);
    }

    private Shape shape(int[] members) {
        long cursor = 0;
        int alignment = 1;
        long[] offsets = new long[members.length];
        for (int i = 0; i < members.length; i++) {
            int width = fields.get(members[i]).kind().width;
            cursor = align(cursor, width);
            offsets[i] = cursor;
            cursor = Math.addExact(cursor, width);
            alignment = Math.max(alignment, width);
        }
        return new Shape(align(cursor, alignment), alignment, offsets);
    }

    private void requireOwner() { memory.nativeOffset(); }

    public int rows() { requireOwner(); return rows; }
    public int physicalBytes() { requireOwner(); return physicalBytes; }
    public long payloadBytes() { requireOwner(); return payloadBytes; }
    public List<Field> fields() { requireOwner(); return fields; }
    public List<Segment> segments() { requireOwner(); return segments; }

    private int indexOf(String field, Kind expected) {
        requireOwner();
        Integer index = field == null ? null : byName.get(field);
        if (index == null) throw new IndexOutOfBoundsException("unknown actor mixed field");
        if (fields.get(index).kind() != expected) {
            throw new IllegalArgumentException("actor mixed field type mismatch");
        }
        return index;
    }

    /** Primitive-only byte locator on all scalar hot paths; no Address facade. */
    private int byteOffset(int row, int index) {
        requireOwner();
        if (row < 0 || row >= rows) {
            throw new IndexOutOfBoundsException("actor mixed row outside live capacity");
        }
        Location loc = locations[index];
        Segment seg = segments.get(loc.segment);
        long inner = layout == Layout.AOSOA
                ? Math.addExact(Math.addExact(
                        Math.multiplyExact(row / lanes, seg.stride), loc.offset),
                        Math.multiplyExact(row % lanes, loc.stride))
                : Math.addExact(Math.multiplyExact(row, loc.stride), loc.offset);
        int width = fields.get(index).kind().width;
        if (inner < 0 || inner > seg.bytes || width > seg.bytes - inner) {
            throw new IndexOutOfBoundsException("actor mixed field outside native segment");
        }
        return Math.toIntExact(Math.addExact(seg.base, inner));
    }

    private int checkedOffset(int row, String field, Kind expected) {
        return byteOffset(row, indexOf(field, expected));
    }

    /** Diagnostic geometry object; ordinary reads/writes use primitive offsets. */
    public Address address(int row, String field) {
        requireOwner();
        Integer index = field == null ? null : byName.get(field);
        if (index == null) throw new IndexOutOfBoundsException("unknown actor mixed field");
        int absolute = byteOffset(row, index);
        Location loc = locations[index];
        Segment seg = segments.get(loc.segment);
        return new Address(loc.segment, absolute, Math.toIntExact(absolute - seg.base),
                fields.get(index).kind());
    }

    public long readU32(int row, String field) {
        int offset = checkedOffset(row, field, Kind.U32);
        synchronized (arena) {
            long word = 0;
            for (int i = 0; i < 4; i++) word |= (long)
                    (memory.readByte(offset + i) & 0xff) << (8 * i);
            return word;
        }
    }
    public void writeU32(int row, String field, long value) {
        int offset = checkedOffset(row, field, Kind.U32);
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("invalid u32");
        synchronized (arena) {
            memory.assertWritable();
            for (int i = 0; i < 4; i++) {
                memory.writeByte(offset + i, (byte) (value >>> (8 * i)));
            }
        }
    }
    /** Stored reference word only; no authority or dereference is conferred. */
    public long readRef32(int row, String field) {
        int offset = checkedOffset(row, field, Kind.REF32);
        synchronized (arena) {
            long word = 0;
            for (int i = 0; i < 4; i++) word |= (long)
                    (memory.readByte(offset + i) & 0xff) << (8 * i);
            return word;
        }
    }
    public void writeRef32(int row, String field, long value) {
        int offset = checkedOffset(row, field, Kind.REF32);
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("invalid ref32 word");
        synchronized (arena) {
            memory.assertWritable();
            for (int i = 0; i < 4; i++) {
                memory.writeByte(offset + i, (byte) (value >>> (8 * i)));
            }
        }
    }
    public double readF64(int row, String field) {
        return memory.readDouble(checkedOffset(row, field, Kind.F64));
    }
    public void writeF64(int row, String field, double value) {
        memory.writeDouble(checkedOffset(row, field, Kind.F64), value);
    }
    public boolean readBool(int row, String field) {
        int raw = Byte.toUnsignedInt(memory.readByte(
                checkedOffset(row, field, Kind.BOOL)));
        if (raw > 1) throw new IllegalStateException("noncanonical actor bool backing byte");
        return raw == 1;
    }
    public void writeBool(int row, String field, boolean value) {
        memory.writeByte(checkedOffset(row, field, Kind.BOOL),
                (byte) (value ? 1 : 0));
    }

    /**
     * Borrow one physically contiguous SoA column without copying or exposing
     * a mutable ByteBuffer. Region leases currently freeze the entire actor
     * native allocation, so this does NOT yet prove field-disjoint borrows.
     * The caller must close the lease before writes, conversion or teardown.
     */
    public ActorNativeArena.Region.ReadOnlyLease leaseSoaColumnReadOnly(String field) {
        requireOwner();
        if (layout != Layout.SOA) {
            throw new UnsupportedOperationException("only SoA columns are contiguous");
        }
        Integer index = field == null ? null : byName.get(field);
        if (index == null) throw new IndexOutOfBoundsException("unknown SoA column");
        Segment segment = segments.get(locations[index].segment);
        return memory.leaseReadOnly(Math.toIntExact(segment.base),
                Math.toIntExact(segment.bytes));
    }

    public void integrate(String position, String velocity, double dt) {
        requireOwner();
        if (!Double.isFinite(dt) || Objects.equals(position, velocity)) {
            throw new IllegalArgumentException("integrate requires distinct finite f64 fields");
        }
        int p = indexOf(position, Kind.F64);
        int v = indexOf(velocity, Kind.F64);
        synchronized (arena) {
            // Freeze-borrow admission before the first write, so a live lease
            // cannot leave a partially updated ECS column.
            memory.assertWritable();
            for (int row = 0; row < rows; row++) {
                int pos = byteOffset(row, p);
                int vel = byteOffset(row, v);
                memory.writeDouble(pos, memory.readDouble(pos)
                        + memory.readDouble(vel) * dt);
            }
        }
    }

    @Override public void close() { memory.close(); }
}
