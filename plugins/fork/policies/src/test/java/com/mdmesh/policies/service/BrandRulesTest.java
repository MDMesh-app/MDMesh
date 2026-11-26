package com.mdmesh.policies.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The same brand keys as the console's normalizeBrand and deviceBrand (model.test.ts). */
public class BrandRulesTest {
    @Test
    public void normalizesAsTheConsoleDoes() {
        assertEquals("oppo", BrandRules.normalize(" OPPO "));
        assertEquals("motorola", BrandRules.normalize("moto"));
        assertEquals("nokia", BrandRules.normalize("HMD  Global"));
        assertEquals("unknown", BrandRules.normalize("  "));
    }

    @Test
    public void handFirstThenReportedThenTheNameThenUnknown() {
        assertEquals("nothing", BrandRules.of("nothing", "OPPO", null, null));
        assertEquals("samsung", BrandRules.of(null, null, "Samsung Electronics", null));
        assertEquals("vivo", BrandRules.of(null, null, null, "Vivo Y05 marketing phone"));
        assertEquals("motorola", BrandRules.of(null, null, null, "Moto-G35 Extra"));
        assertEquals("unknown", BrandRules.of(null, null, null, "Front desk tablet"));
    }
}
