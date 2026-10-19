package dev.oreslang.dop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDK-only executable reference checks, intentionally free of JUnit dependencies. */
public final class SoaSnapshotSmoke {
    private SoaSnapshotSmoke() { }

    public static void main(String[] args) {
        runAll();
        System.out.println("SOA_SNAPSHOT_SMOKE_PASS");
    }

    public static void runAll() {
        Map<String, Class<?>> schema = new LinkedHashMap<>();
        schema.put("first name", String.class);
        schema.put("age", Integer.class);
        schema.put("active?", Boolean.class);
        List<Map<String, ?>> rows = new ArrayList<>();
        Map<String, Object> ada = new LinkedHashMap<>();
        ada.put("age", 37);
        ada.put("active?", true);
        ada.put("first name", "Ada");
        rows.add(ada);
        rows.add(Map.of("active?", false, "first name", "Grace", "age", 42));

        SoaSnapshot snapshot = SoaSnapshot.fromRows(schema, rows);
        expect(snapshot.rowCount() == 2, "row count");
        expect(snapshot.column("age").equals(List.of(37, 42)), "contiguous age values");
        expect(snapshot.toRows().equals(rows), "AoS -> SoA -> AoS equality");
        expect(SoaSnapshot.fromColumns(schema, snapshot.columns()).toRows().equals(rows),
                "column reconstruction");
        expect(SoaSnapshot.fromRows(schema, List.of()).rowCount() == 0, "typed empty table");
        expect(SoaSnapshot.fromRows(Map.of(), List.of(Map.of(), Map.of())).rowCount() == 2,
                "zero-field row count");

        ada.put("age", 99);
        rows.clear();
        expect(snapshot.column("age").equals(List.of(37, 42)), "input copy isolation");
        denied(() -> snapshot.column("age").add(0), "immutable columns");
        denied(() -> snapshot.toRows().get(0).put("age", 99), "immutable rows");
        denied(() -> snapshot.schema().clear(), "immutable schema");

        denied(() -> SoaSnapshot.fromRows(schema, List.of(Map.of("age", 37))),
                "missing shape");
        denied(() -> SoaSnapshot.fromRows(schema, List.of(Map.of(
                "age", "37", "first name", "Ada", "active?", true))), "type mismatch");
        denied(() -> SoaSnapshot.fromColumns(schema, Map.of(
                "age", List.of(1, 2), "first name", List.of("Ada"),
                "active?", List.of(true, false))), "mismatched lengths");
        denied(() -> SoaSnapshot.fromColumns(schema, Map.of(
                "age", List.of(1, 2), "first name", List.of("Ada", "Grace"),
                "active?", List.of(true, "not-a-bool"))), "wrong column element type");
        denied(() -> SoaSnapshot.fromColumns(Map.of(), Map.of()), "ambiguous empty columns");
        denied(() -> SoaSnapshot.fromRows(Map.of("ref", Object.class), List.of()),
                "host object forbidden");
        denied(() -> SoaSnapshot.fromRows(Map.of("x", Integer.class),
                java.util.Collections.nCopies(SoaSnapshot.MAX_CELLS + 1, Map.of("x", 1))),
                "capacity bounded");
        denied(() -> SoaSnapshot.fromRows(Map.of("bad" + (char) 0 + "field", String.class), List.of()),
                "NUL forbidden");
        expect(SoaSnapshot.fromRows(Map.of("", String.class),
                List.of(Map.of("", "value"))).column("").equals(List.of("value")),
                "empty quoted key");
        randomizedRoundTrips();
    }

    private static void randomizedRoundTrips() {
        java.util.Random random = new java.util.Random(0x50A2026L);
        Map<String, Class<?>> schema = new LinkedHashMap<>();
        schema.put("first name", String.class);
        schema.put("Σ", Double.class);
        schema.put("is active?", Boolean.class);
        for (int trial = 0; trial < 300; trial++) {
            int count = random.nextInt(20);
            List<Map<String, ?>> rows = new ArrayList<>();
            for (int row = 0; row < count; row++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("is active?", random.nextBoolean());
                item.put("Σ", random.nextDouble());
                item.put("first name", "person-" + trial + "-" + row);
                rows.add(item);
            }
            SoaSnapshot columnar = SoaSnapshot.fromRows(schema, rows);
            expect(columnar.rowCount() == count, "random row count " + trial);
            expect(columnar.toRows().equals(rows), "random AoS roundtrip " + trial);
            Map<String, List<Object>> reordered = new LinkedHashMap<>();
            reordered.put("is active?", columnar.column("is active?"));
            reordered.put("first name", columnar.column("first name"));
            reordered.put("Σ", columnar.column("Σ"));
            expect(SoaSnapshot.fromColumns(schema, reordered).toRows().equals(rows),
                    "column order independent " + trial);
        }
    }

    private static void expect(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
    }

    private static void denied(Runnable action, String name) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException
                | UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("unexpectedly accepted: " + name);
    }
}
