package dev.oreslang;

import dev.oreslang.compiler.ZordeSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/reference checked-scalar ownership only, NOT guest Valid<T, SchemaId>.
 * Plans can originate from arbitrary analysis source: only target revalidation
 * permits an explicit handoff and nobody may unwrap the same holder twice.
 */
final class ZordeCheckedScalarMoveTest {

    @Test void moveIsOneShotAndRevalidationIsFailureAtomic() {
        var plan = ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=1,max=100) type Small = int; "
                + "@Range(min=5,max=25) type Narrow = int; "
                + "@Range(min=1,max=200) type Wide = int;");
        var value = plan.tryFrom("app.Small", 90L).value();

        var rejected = plan.revalidateInto("app.Narrow", value);
        assertFalse(rejected.ok());
        assertEquals("constraint_violation", rejected.error().code());
        assertEquals("app.Narrow", rejected.error().qualifiedTypeName());

        var accepted = plan.revalidateInto("app.Wide", value);
        assertTrue(accepted.ok());
        assertEquals("app.Wide", accepted.value().qualifiedTypeName());
        assertEquals(plan.schema("app.Wide").fingerprint(),
                accepted.value().schemaFingerprint());
        assertThrows(IllegalStateException.class, value::intoInner);
        assertThrows(IllegalStateException.class,
                () -> plan.revalidateInto("app.Wide", value));

        assertEquals(90L, accepted.value().intoInner());
        assertThrows(IllegalStateException.class, accepted.value()::intoInner);
    }

    @Test void forgedFingerprintOrIndependentPlanIsNeverAuthority() {
        var untrusted = ZordeSchemaGenerator.planSource(
                "namespace attacker; type Forged = int with value gte 0;");
        var trusted = ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=1,max=10) type Port = int;");
        var source = untrusted.tryFrom("attacker.Forged", 200L).value();

        // The source's public fingerprint must never bypass the target rule.
        assertNotEquals(trusted.schema("app.Port").fingerprint(),
                source.schemaFingerprint());
        assertFalse(trusted.revalidateInto("app.Port", source).ok());
        assertEquals(200L, source.intoInner());

        var admissible = untrusted.tryFrom("attacker.Forged", 7L).value();
        var moved = trusted.revalidateInto("app.Port", admissible);
        assertTrue(moved.ok());
        assertEquals(7L, moved.value().intoInner());
        assertThrows(IllegalStateException.class, admissible::intoInner);

        var malformed = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) type Name = string;");
        assertFalse(malformed.tryFrom("app.Name",
                String.valueOf((char) 0xD800)).ok());
        assertFalse(trusted.tryFrom("app.Port", 7.0).ok());
    }

    @Test void concurrentUnwrapHasExactlyOneWinner() throws Exception {
        var plan = ZordeSchemaGenerator.planSource(
                "namespace app; type Port = int with value gte 1;");
        var scalar = plan.tryFrom("app.Port", 443L).value();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                outcomes.add(executor.submit(() -> {
                    start.await();
                    try {
                        return scalar.intoInner().equals(443L);
                    } catch (IllegalStateException alreadyConsumed) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (var result : outcomes) {
                if (result.get(10, TimeUnit.SECONDS)) succeeded++;
            }
            assertEquals(1, succeeded);
            assertThrows(IllegalStateException.class, scalar::intoInner);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
