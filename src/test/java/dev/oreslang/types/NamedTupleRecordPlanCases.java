package dev.oreslang.types;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import static dev.oreslang.types.NamedTupleRecordPlan.*;

public final class NamedTupleRecordPlanCases {
    private static final Predicate<String> VALID = s -> s.matches("[a-z][a-z0-9_]*");
    private static void equal(Object a, Object b) {
        if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b);
    }
    private static void reject(Runnable code, String diagnostic) {
        try { code.run(); }
        catch (IllegalArgumentException e) {
            if (e.getMessage().startsWith(diagnostic)) return;
            throw e;
        }
        throw new AssertionError("expected " + diagnostic);
    }
    public static void main(String[] args) {
        int cases = 0;
        // Independent reference: scan keys and first/last positions separately.
        for (int n = 0; n <= 7; n++) {
            int combinations = 1;
            for (int i = 0; i < n; i++) combinations *= 3;
            for (int mask = 0; mask < combinations; mask++) {
                var slots = new ArrayList<Slot<String>>();
                var keys = new ArrayList<String>();
                int k = mask;
                for (int i = 0; i < n; i++) {
                    String key = String.valueOf((char) ('a' + k % 3));
                    k /= 3;
                    slots.add(new Slot<>(key, "type_" + i));
                    if (!keys.contains(key)) keys.add(key);
                }
                for (Duplicates policy : Duplicates.values()) {
                    cases++;
                    if (policy == Duplicates.REJECT && keys.size() != n) {
                        reject(() -> bind(slots, policy, VALID), "E_RECORD_DUPLICATE");
                        continue;
                    }
                    var plan = bind(slots, policy, VALID);
                    var expected = new ArrayList<Field<String>>();
                    for (String key : keys) {
                        int chosen = -1;
                        for (int i = 0; i < n; i++) {
                            if (slots.get(i).key().equals(key)) {
                                chosen = i;
                                if (policy != Duplicates.LAST) break;
                            }
                        }
                        expected.add(new Field<>(key, "type_" + chosen, chosen));
                    }
                    equal(plan.fields(), expected);
                    var discarded = new ArrayList<Integer>();
                    for (int i = 0; i < n; i++) {
                        boolean kept = false;
                        for (var field : expected) if (field.sourceSlot() == i) kept = true;
                        if (!kept) discarded.add(i);
                    }
                    equal(plan.discarded(), discarded);
                }
            }
        }
        for (Duplicates policy : Duplicates.values()) {
            reject(() -> bind(List.of(new Slot<String>(null, "int")), policy, VALID), "E_RECORD_KEY");
            reject(() -> bind(List.of(new Slot<>("bad-key", "int")), policy, VALID), "E_RECORD_KEY");
            reject(() -> bind(List.of(new Slot<>("a", "int"), new Slot<>("a", "string"),
                    new Slot<>("", "int")), policy, VALID),
                    policy == Duplicates.REJECT ? "E_RECORD_DUPLICATE" : "E_RECORD_KEY");
            cases += 3;
        }
        var boundary = new ArrayList<Slot<String>>();
        for (int i = 0; i < 4096; i++) boundary.add(new Slot<>("a" + i, "int"));
        equal(bind(boundary, Duplicates.REJECT, VALID).fields().size(), 4096);
        boundary.add(new Slot<>("extra", "int"));
        reject(() -> bind(boundary, Duplicates.REJECT, VALID), "E_RECORD_SHAPE_LIMIT");
        cases += 2;
        var plan = bind(List.of(new Slot<>("a", "int"), new Slot<>("a", "string")), Duplicates.LAST, VALID);
        try { plan.fields().clear(); throw new AssertionError("mutable fields"); }
        catch (UnsupportedOperationException expected) {}
        try { plan.discarded().clear(); throw new AssertionError("mutable discards"); }
        catch (UnsupportedOperationException expected) {}
        cases += 2;
        var members = new LinkedHashMap<String, String>();
        members.put("z", "string");
        members.put("a", "int");
        members.put("m", "bool");
        var expectedCanonical = List.of(new Slot<>("a", "int"), new Slot<>("m", "bool"), new Slot<>("z", "string"));
        equal(reverse(members, null, VALID), expectedCanonical);
        equal(reverse(members, List.of("z", "a", "m"), VALID),
                List.of(new Slot<>("z", "string"), new Slot<>("a", "int"), new Slot<>("m", "bool")));
        equal(reverse(Map.of("a", "int", "m", "bool", "z", "string"), null, VALID), expectedCanonical);
        equal(reverse(Map.<String,String>of(), null, VALID), List.of());
        reject(() -> reverse(members, List.of("a", "a", "m"), VALID), "E_RECORD_ORDER");
        reject(() -> reverse(members, List.of("a", "z"), VALID), "E_RECORD_ORDER");
        reject(() -> reverse(members, List.of("a", "m", "unknown"), VALID), "E_RECORD_ORDER");
        reject(() -> reverse(Map.of("bad-key", "int"), null, VALID), "E_RECORD_KEY");
        try { reverse(members, null, VALID).clear(); throw new AssertionError("mutable reverse shape"); }
        catch (UnsupportedOperationException expected) {}
        var large = new LinkedHashMap<String,String>();
        for (int i = 0; i < 4096; i++) large.put("a" + i, "int");
        equal(reverse(large, null, VALID).size(), 4096);
        large.put("extra", "int");
        reject(() -> reverse(large, null, VALID), "E_RECORD_SHAPE_LIMIT");
        cases += 11;
        System.out.println("NAMED_TUPLE_RECORD_PLAN_PASS cases=" + cases);
    }
}
