package dev.oreslang;

import dev.oreslang.types.DataLayoutAdvisor;
import dev.oreslang.types.DataLayoutAdvisor.Hints;
import dev.oreslang.types.DataLayoutAdvisor.Layout;
import dev.oreslang.types.DataLayoutAdvisor.Region;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class DataLayoutAdvisorTest {
    private Region region(Layout layout, Hints hints, boolean whole) {
        return new Region("actors.scheduler.metadata", layout, 10_000,
                List.of(4, 4, 4, 4, 8, 8, 8, 8), List.of(0, 1), true,
                true, true, whole, false, true, false, hints);
    }
    private boolean has(Region region, String code) {
        return DataLayoutAdvisor.analyze(region).stream().anyMatch(f -> f.code().equals(code));
    }

    @Test void suggestsSoaForSelectiveAoSButNeverCertifiesRewrite() {
        var r = region(Layout.AOS, Hints.unknown(), false);
        assertTrue(has(r, "ORES-LAYOUT101"));
        var invalid = new Region(r.symbol(), r.layout(), 10_000, r.fieldBytes(),
                r.accessedFields(), true, true, true, false, false, false, true,
                Hints.unknown());
        assertFalse(has(invalid, "ORES-LAYOUT101"));
    }
    @Test void warnsWhenSoaIsUsedForWholeRecords() {
        assertTrue(has(region(Layout.SOA, Hints.unknown(), true), "ORES-LAYOUT131"));
    }
    @Test void warnsOnEcsChurnAndKnownDenseMembership() {
        assertTrue(has(region(Layout.ARCHETYPE, new Hints(null, true, 1500L, null, null), false),
                "ORES-LAYOUT134"));
        assertTrue(has(region(Layout.ARCHETYPE, new Hints(null, null, 1500L, null, null), false),
                "ORES-LAYOUT135"));
        assertFalse(has(region(Layout.ARCHETYPE, Hints.unknown(), false), "ORES-LAYOUT135"));
    }
    @Test void checksAoSoaTilesWithoutGuessingUnknownVectorWidth() {
        assertTrue(has(region(Layout.AOSOA, new Hints(null, null, null, 256, 32), false),
                "ORES-LAYOUT133"));
        assertFalse(has(region(Layout.AOSOA, new Hints(null, null, null, 256, null), false),
                "ORES-LAYOUT133"));
    }
    @Test void rejectsMalformedEvidenceAndCopiesCallerCollections() {
        assertThrows(IllegalArgumentException.class, () -> new Region(
                "s", Layout.AOS, 128, List.of(4, 4), List.of(0, 0),
                true, true, true, false, false, true, false, Hints.unknown()));
        assertThrows(IllegalArgumentException.class, () ->
                region(Layout.AOS, new Hints(null, null, null, 16, 32), false));
        var fields = new java.util.ArrayList<>(List.of(4, 8, 4, 8));
        var region = new Region("s", Layout.AOS, 1000, fields, List.of(0),
                true, true, true, false, false, true, false, Hints.unknown());
        fields.set(0, 999);
        assertEquals(4, region.fieldBytes().get(0));
    }
}
