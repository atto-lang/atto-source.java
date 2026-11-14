package dev.oreslang;

import dev.oreslang.compiler.StateRegionLoanReceipt;
import dev.oreslang.compiler.StateRegionLoanReceipt.Action;
import dev.oreslang.compiler.StateRegionLoanReceipt.Kind;
import dev.oreslang.compiler.StateRegionLoanReceipt.Provenance;
import dev.oreslang.compiler.StateRegionLoanReceipt.Requirement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Host contract only: these tests do NOT exercise guest source lowering. */
final class StateRegionLoanReceiptTest {
    private static Provenance borrow() {
        return new Provenance(9, 3, 7, 1001, 41, 6, 501,
                "inventory.stock", Kind.WRITE, Action.BORROW);
    }

    @Test
    void exact_release_and_nine_canonical_negative_vectors() {
        Provenance b = borrow();
        Provenance r = b.release_record();
        assertTrue(b.authorizes_release(r));
        assertFalse(r.authorizes_release(b));
        assertFalse(b.authorizes_release(b));
        assertThrows(IllegalStateException.class, r::release_record);
        assertFalse(b.authorizes_release(null));
        Provenance[] invalid = {
                new Provenance(9, 3, 7, 1002, 41, 6, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 8, 1001, 41, 6, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 7, 1001, 41, 5, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 7, 1001, 42, 6, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(10, 3, 7, 1001, 41, 6, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 2, 7, 1001, 41, 6, 501, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 7, 1001, 41, 6, 502, "inventory.stock", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 7, 1001, 41, 6, 501, "inventory.reserved", Kind.WRITE, Action.RELEASE),
                new Provenance(9, 3, 7, 1001, 41, 6, 501, "inventory.stock", Kind.READ, Action.RELEASE)
        };
        assertEquals(9, invalid.length);
        for (int index = 0; index < invalid.length; index++) {
            assertFalse(b.authorizes_release(invalid[index]), "invalid receipt " + index);
        }
    }

    @Test
    void all_seven_identity_components_reject_unsafe_number_aliases() {
        long max = StateRegionLoanReceipt.MAX_JSON_SAFE_ID;
        var top = new Provenance(max, max, max, max, max, max, max,
                "inventory.stock", Kind.READ, Action.BORROW);
        assertTrue(top.authorizes_release(top.release_record()));
        for (int component = 0; component < 7; component++) {
            final int at = component;
            long[] fields = {9, 3, 7, 1001, 41, 6, 501};
            fields[at] = max + 1;
            assertThrows(IllegalArgumentException.class, () ->
                    new Provenance(fields[0], fields[1], fields[2],
                            fields[3], fields[4], fields[5], fields[6],
                            "inventory.stock", Kind.WRITE, Action.BORROW),
                    "unsafe identity " + at);
            fields[at] = 0;
            assertThrows(IllegalArgumentException.class, () ->
                    new Provenance(fields[0], fields[1], fields[2],
                            fields[3], fields[4], fields[5], fields[6],
                            "inventory.stock", Kind.WRITE, Action.BORROW),
                    "zero identity " + at);
        }
        assertThrows(IllegalArgumentException.class, () ->
                new Provenance(9, 3, 7, 1001, 41, 6, 501,
                        " ", Kind.WRITE, Action.BORROW));
        assertThrows(NullPointerException.class, () ->
                new Provenance(9, 3, 7, 1001, 41, 6, 501,
                        "inventory.stock", null, Action.BORROW));
    }

    @Test
    void symbolic_requirement_is_fenced_and_has_no_concrete_runtime_ids() {
        Requirement b = new Requirement("inventory.state", true, true, 501,
                "inventory.stock", Kind.WRITE, Action.BORROW);
        Requirement r = b.release_requirement();
        assertTrue(b.authorizes_release(r));
        assertFalse(r.authorizes_release(b));
        assertThrows(IllegalStateException.class, r::release_requirement);
        assertFalse(b.authorizes_release(null));
        assertFalse(b.authorizes_release(new Requirement(
                "different.state", true, true, 501,
                "inventory.stock", Kind.WRITE, Action.RELEASE)));
        assertFalse(b.authorizes_release(new Requirement(
                "inventory.state", true, true, 502,
                "inventory.stock", Kind.WRITE, Action.RELEASE)));
        assertThrows(IllegalArgumentException.class, () ->
                new Requirement("inventory", false, true, 501,
                        "inventory.stock", Kind.WRITE, Action.BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new Requirement("inventory", true, false, 501,
                        "inventory.stock", Kind.WRITE, Action.BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new Requirement("inventory", true, true,
                        StateRegionLoanReceipt.MAX_JSON_SAFE_ID + 1,
                        "inventory.stock", Kind.WRITE, Action.BORROW));
        assertThrows(IllegalArgumentException.class, () ->
                new Requirement("inventory", true, true, 0,
                        "inventory.stock", Kind.WRITE, Action.BORROW));

        Set<String> names = Arrays.stream(Requirement.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        for (String runtimeField : new String[]{
                "region_id", "region_generation", "runtime_domain", "execution_id",
                "execution_slot", "execution_generation"
        }) assertFalse(names.contains(runtimeField), runtimeField + " leaked into symbolic requirement");
        assertEquals(7, names.size());
    }
}
