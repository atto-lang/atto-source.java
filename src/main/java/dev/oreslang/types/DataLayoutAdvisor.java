package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Compiler-side, source-evidence-based layout advisory for Oreslang code.
 *
 * This pass never changes layout or grants a borrow/actor access permit.
 * Callers must prove hot-path facts from typed compiler analysis or a profiler.
 * The canonical cross-backend advisory contract lives in the Rust analyzer.
 */
public final class DataLayoutAdvisor {
    private DataLayoutAdvisor() { }

    public enum Layout { AOS, SOA, AOSOA, SPARSE_SET, ARCHETYPE }

    /** Nullable hints mean unknown, NOT false or zero. */
    public record Hints(Boolean randomGathers, Boolean denseStableMembership,
                        Long structuralChangesPerPass, Integer tileRows,
                        Integer vectorBytes) {
        public Hints {
            if (structuralChangesPerPass != null && structuralChangesPerPass < 0) {
                throw new IllegalArgumentException("negative ECS mutation count");
            }
            if (vectorBytes != null && (vectorBytes < 8 ||
                    (vectorBytes & (vectorBytes - 1)) != 0)) {
                throw new IllegalArgumentException("unknown or invalid target vector width");
            }
        }
        public static Hints unknown() { return new Hints(null, null, null, null, null); }
    }

    public record Region(String symbol, Layout layout, long rows,
                         List<Integer> fieldBytes, List<Integer> accessedFields,
                         boolean hot, boolean dense, boolean sequential,
                         boolean wholeRecord, boolean independent,
                         boolean relocationVerified, boolean stableAddress,
                         Hints hints) {
        public Region {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(layout);
            Objects.requireNonNull(fieldBytes);
            Objects.requireNonNull(accessedFields);
            Objects.requireNonNull(hints);
            if (symbol.isBlank() || symbol.length() > 1024 || rows < 0 ||
                    fieldBytes.isEmpty() || fieldBytes.size() > 16384 ||
                    accessedFields.size() > 16384) {
                throw new IllegalArgumentException("invalid bounded region evidence");
            }
            fieldBytes = List.copyOf(fieldBytes);
            accessedFields = List.copyOf(accessedFields);
            var seen = new HashSet<Integer>();
            for (int width : fieldBytes) {
                if (width <= 0) throw new IllegalArgumentException("invalid field width");
            }
            for (int index : accessedFields) {
                if (index < 0 || index >= fieldBytes.size() || !seen.add(index)) {
                    throw new IllegalArgumentException("duplicate or invalid field access");
                }
            }
            if (hints.tileRows() != null &&
                    (layout != Layout.AOSOA || hints.tileRows() < 2 ||
                     hints.tileRows() > 4096)) {
                throw new IllegalArgumentException("invalid AoSoA tile evidence");
            }
        }
    }

    public record Finding(String code, String message) { }

    public static List<Finding> analyze(Region r) {
        Objects.requireNonNull(r);
        var findings = new ArrayList<Finding>();
        if (!r.hot() || r.rows() < 128) return List.of();
        long total = 0, selected = 0;
        for (int width : r.fieldBytes()) total = Math.addExact(total, width);
        for (int index : r.accessedFields()) selected = Math.addExact(selected, r.fieldBytes().get(index));
        boolean stream = r.dense() && r.sequential();
        if (r.layout() == Layout.AOS && stream && r.relocationVerified() &&
                !r.stableAddress() && !r.wholeRecord() && r.fieldBytes().size() >= 4 &&
                selected > 0 && selected * 2 <= total) {
            findings.add(new Finding("ORES-LAYOUT101", "Benchmark SoA for this field-selective AoS pass."));
        }
        if (r.layout() == Layout.SOA && stream && r.wholeRecord()) {
            findings.add(new Finding("ORES-LAYOUT131", "Benchmark AoS/AoSoA for hot whole-record SoA traversal."));
        }
        if ((r.layout() == Layout.SOA || r.layout() == Layout.AOSOA ||
                r.layout() == Layout.ARCHETYPE) && Boolean.TRUE.equals(r.hints().randomGathers())) {
            findings.add(new Finding("ORES-LAYOUT132", "Profile random cross-column gathers and TLB locality."));
        }
        if (r.layout() == Layout.AOSOA && r.hints().tileRows() != null &&
                r.hints().vectorBytes() != null && !r.accessedFields().isEmpty()) {
            int widest = r.accessedFields().stream().mapToInt(r.fieldBytes()::get).max().orElse(0);
            if (widest > 0 && (long) r.hints().vectorBytes() >= 2L * widest) {
                int ideal = Math.max(2, Math.min(64, r.hints().vectorBytes() / widest));
                int actual = r.hints().tileRows();
                if (actual < Math.max(2, ideal / 2) || (long) actual > 4L * ideal) {
                    findings.add(new Finding("ORES-LAYOUT133", "Benchmark AoSoA tiles and SIMD/tail costs."));
                }
            }
        }
        if ((r.layout() == Layout.SPARSE_SET || r.layout() == Layout.ARCHETYPE) &&
                stream && !r.independent() && Boolean.TRUE.equals(r.hints().denseStableMembership())) {
            findings.add(new Finding("ORES-LAYOUT134", "Benchmark dense SoA or fused archetype iteration."));
        }
        if ((r.layout() == Layout.SPARSE_SET || r.layout() == Layout.ARCHETYPE) &&
                r.hints().structuralChangesPerPass() != null &&
                r.hints().structuralChangesPerPass() >= r.rows() / 8 + (r.rows() % 8 == 0 ? 0 : 1)) {
            findings.add(new Finding("ORES-LAYOUT135", "Benchmark deferred ECS structural-change batches and generation-safe handles."));
        }
        return List.copyOf(findings);
    }
}
