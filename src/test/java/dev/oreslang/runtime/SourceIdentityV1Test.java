package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Native Java source-ID table tests; NOT guest instrumented tracing proof. */
class SourceIdentityV1Test {
    @Test
    void validatesIndependentFixedWidthSourceIdentities() {
        var routine = SourceIdentityV1.parse(
                "rid_d5f8e146ba97430dac6bf17430477e2a", SourceIdentityV1.Kind.ROUTINE);
        var site = SourceIdentityV1.parse(
                "sid_652e1632e7464fdb936fc40fe24f1c7b", SourceIdentityV1.Kind.SITE);
        var result = SourceIdentityV1.freezeValidated(
                List.of(new SourceIdentityV1.Site(routine, site, 17)));
        assertEquals(1, result.size());
        assertEquals(17, result.get(0).descriptorIndex());
        assertThrows(UnsupportedOperationException.class, () ->
                result.add(new SourceIdentityV1.Site(routine, site, 99)));
    }

    @Test
    void rejectsWrongKindDynamicIdAndUppercaseWithoutTracingFallback() {
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.parse("sid_d5f8e146ba97430dac6bf17430477e2a",
                        SourceIdentityV1.Kind.ROUTINE));
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.parse("rid_D5f8e146ba97430dac6bf17430477e2a",
                        SourceIdentityV1.Kind.ROUTINE));
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.parse("rid_00000000000000000000000000000000",
                        SourceIdentityV1.Kind.ROUTINE));
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.parse("4bf92f3577b34da6a3ce929d0e0e4736",
                        SourceIdentityV1.Kind.ROUTINE));
    }

    @Test
    void fuzzesFixedWidthIdsAndRejectsInvalidMutations() {
        var random = new Random(0x62e1d4b76027190cL);
        for (int n = 0; n < 2000; n++) {
            var raw = new byte[16];
            random.nextBytes(raw);
            var value = "rid_" + HexFormat.of().formatHex(raw);
            var parsed = SourceIdentityV1.parse(value, SourceIdentityV1.Kind.ROUTINE);
            var lanes = ByteBuffer.wrap(raw);
            assertEquals(lanes.getLong(), parsed.high());
            assertEquals(lanes.getLong(), parsed.low());
            for (int i = 4; i < 36; i++) {
                var malformed = value.substring(0, i) + "G" + value.substring(i + 1);
                assertThrows(IllegalArgumentException.class, () ->
                        SourceIdentityV1.parse(malformed, SourceIdentityV1.Kind.ROUTINE));
            }
            assertThrows(IllegalArgumentException.class, () ->
                    SourceIdentityV1.parse(value, SourceIdentityV1.Kind.SITE));
        }
    }

    @Test
    void rejectsForgedAllZeroIdEvenWhenConstructedDirectly() {
        assertThrows(IllegalArgumentException.class, () ->
                new SourceIdentityV1.Id(SourceIdentityV1.Kind.ROUTINE, 0L, 0L));
        assertThrows(NullPointerException.class, () ->
                new SourceIdentityV1.Id(null, 1L, 2L));
    }

    @Test
    void rejectsDuplicateSiteAndDescriptorIndex() {
        var routine = SourceIdentityV1.parse(
                "rid_d5f8e146ba97430dac6bf17430477e2a", SourceIdentityV1.Kind.ROUTINE);
        var first = SourceIdentityV1.parse(
                "sid_652e1632e7464fdb936fc40fe24f1c7b", SourceIdentityV1.Kind.SITE);
        var second = SourceIdentityV1.parse(
                "sid_738b13448f724fc5905841e9037b410a", SourceIdentityV1.Kind.SITE);
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.freezeValidated(List.of(
                        new SourceIdentityV1.Site(routine, first, 1),
                        new SourceIdentityV1.Site(routine, first, 2))));
        assertThrows(IllegalArgumentException.class, () ->
                SourceIdentityV1.freezeValidated(List.of(
                        new SourceIdentityV1.Site(routine, first, 1),
                        new SourceIdentityV1.Site(routine, second, 1))));
        assertTrue(SourceIdentityV1.freezeValidated(List.of(
                new SourceIdentityV1.Site(routine, first, 1),
                new SourceIdentityV1.Site(routine, second, 2))).size() == 2);
    }
}
