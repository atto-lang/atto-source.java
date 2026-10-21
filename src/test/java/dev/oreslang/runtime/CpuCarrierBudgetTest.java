package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class CpuCarrierBudgetTest {
    @Test
    void cgroupV2QuotaParsesFractionalCpuAndUnlimited() {
        assertEquals(2, CpuCarrierBudget.parseCpuMax("150000 100000\n"));
        assertEquals(1, CpuCarrierBudget.parseCpuMax("50000 100000"));
        assertEquals(8, CpuCarrierBudget.parseCpuMax("800000 100000"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("max 100000"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("0 100000"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("-1 100000"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("oops"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("18446744073709551615 1"));
        assertEquals(Integer.MAX_VALUE,
                CpuCarrierBudget.parseCpuMax("9223372036854775807 1"));
    }

    @Test
    void legacyQuotaAndEffectiveCpusetParseFailClosed() {
        assertEquals(3, CpuCarrierBudget.parseCfsQuota("250000\n", "100000\n"));
        assertEquals(0, CpuCarrierBudget.parseCfsQuota("-1", "100000"));
        assertEquals(0, CpuCarrierBudget.parseCfsQuota("100000", "0"));
        assertEquals(6, CpuCarrierBudget.parseCpuSet("0-2,5,8-9\n"));
        assertEquals(1, CpuCarrierBudget.parseCpuSet("64"));
        assertEquals(0, CpuCarrierBudget.parseCpuSet(""));
        assertEquals(0, CpuCarrierBudget.parseCpuSet("1-0"));
        assertEquals(0, CpuCarrierBudget.parseCpuSet("0-3,2-5"));
        assertEquals(0, CpuCarrierBudget.parseCpuSet("0,0"));
        assertEquals(0, CpuCarrierBudget.parseCpuSet("1,,3"));
    }

    @Test
    void budgetRespectsMostRestrictiveKnownResource() {
        assertEquals(2, CpuCarrierBudget.clamp(24, 4, 2));
        assertEquals(4, CpuCarrierBudget.clamp(24, 4, 0));
        assertEquals(3, CpuCarrierBudget.clamp(24, 0, 3));
        assertEquals(24, CpuCarrierBudget.clamp(24, 0, 0));
        assertEquals(1, CpuCarrierBudget.clamp(1, 4, 8));
    }

    @Test
    void domainDefaultsAvoidThreeFullCpuPools() {
        for (int cap = 3; cap <= 128; cap++) {
            var pools = CpuCarrierBudget.defaults(cap);
            assertEquals(cap, pools.total(), "capacity=" + cap);
            assertTrue(pools.privateWorkers() >= 1);
            assertTrue(pools.sharedWorkers() >= 1);
            assertEquals(pools.privateWorkers(), pools.untrustedWorkers());
        }
        assertEquals(3, CpuCarrierBudget.defaults(1).total(),
                "three physically independent schedulers require >=3 carriers");
    }
}
