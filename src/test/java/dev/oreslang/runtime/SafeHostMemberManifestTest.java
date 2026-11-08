package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only v0 manifest contract. Guest interpreter and native-image execution
 * require independent exact-head, nonzero-step receipts.
 */
final class SafeHostMemberManifestTest {
    @Test
    void onlyApprovedPublicStaticLongSignatureResolves() throws Exception {
        String approved = SafeHostMemberManifest.LONG_SUM_I64;
        assertEquals("java.lang.Long#sum(long,long)", approved);
        assertTrue(SafeHostMemberManifest.isMemberSelector(approved));
        assertEquals("java.lang.Long", SafeHostMemberManifest.className(approved));
        Method method = SafeHostMemberManifest.resolve(approved);
        assertEquals(Long.class, method.getDeclaringClass());
        assertEquals(Long.TYPE, method.getReturnType());
        assertArrayEquals(new Class<?>[] {long.class, long.class}, method.getParameterTypes());
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(42L, ((Long) method.invoke(null, 40L, 2L)).longValue());
    }

    @Test
    void forgedSelectorsCannotBecomeManifestEntries() {
        assertFalse(SafeHostMemberManifest.isMemberSelector(null));
        assertFalse(SafeHostMemberManifest.isMemberSelector("java.lang.Long"));
        for (String forged : new String[] {
                "",
                "java.lang.Long",
                "java.lang.Long#sum",
                "java.lang.Long#sum(int,int)",
                "java.lang.Long#sum(long,long) ",
                "java.lang.Long#sum(long,long)#toString",
                "java.lang.Long#toString(long)",
                "java.lang.Long#valueOf(long)",
                "java.lang.Runtime#exec(java.lang.String)",
                "java.lang.Class#forName(java.lang.String)"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> SafeHostMemberManifest.className(forged),
                    "Unexpected class lookup from selector: " + forged);
            assertThrows(IllegalArgumentException.class,
                    () -> SafeHostMemberManifest.resolve(forged),
                    "Unexpected method admission from selector: " + forged);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SafeHostMemberManifest.resolve(null));
    }

    @Test
    void approvedManifestDoesNotResolveAdjacentLongOverloadsOrConstructors() {
        Method approved = SafeHostMemberManifest.resolve(SafeHostMemberManifest.LONG_SUM_I64);
        assertEquals("sum", approved.getName());
        assertEquals(2, approved.getParameterCount());
        assertNotEquals("toString", approved.getName());
        assertNotEquals("valueOf", approved.getName());
        assertThrows(IllegalArgumentException.class,
                () -> SafeHostMemberManifest.resolve("java.lang.Long#sum(int,int)"));
    }
}
