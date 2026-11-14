package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
