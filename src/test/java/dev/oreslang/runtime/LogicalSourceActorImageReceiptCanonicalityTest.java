package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed structural preparation of compiler-owned image receipts.
 * No malformed metadata can become an authoritative source actor Route,
 * independently of whether an actor generation has already linked.
 */
final class LogicalSourceActorImageReceiptCanonicalityTest {
    private static final String VALID = "0123456789abcdef".repeat(4);

    @Test
    void canonicalImageReceiptsAreImmutableAndPreserveExactHashes() {
        var image = new LogicalSourceActorRouterV1.ImageReceipts(
                VALID, "a".repeat(64), "f".repeat(64));
        assertEquals(VALID, image.linkedCodeSha256());
        assertEquals("a".repeat(64), image.stateSchemaSha256());
        assertEquals("f".repeat(64), image.messageAbiSha256());
    }

    @Test
    void malformedDigestInAnyCompilerDomainRejectsTheWholeImage() {
        for (String bad : new String[] {
                null, "", "0".repeat(63), "0".repeat(65),
                "A".repeat(64), "g".repeat(64),
                "0".repeat(63) + " ", "0".repeat(63) + Character.toString((char) 233),
                "0".repeat(63) + Character.toString((char) 0)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LogicalSourceActorRouterV1.ImageReceipts(
                            bad, VALID, VALID),
                    "bad linked-code fingerprint must fail");
            assertThrows(IllegalArgumentException.class,
                    () -> new LogicalSourceActorRouterV1.ImageReceipts(
                            VALID, bad, VALID),
                    "bad persistent-state layout fingerprint must fail");
            assertThrows(IllegalArgumentException.class,
                    () -> new LogicalSourceActorRouterV1.ImageReceipts(
                            VALID, VALID, bad),
                    "bad receive-message ABI fingerprint must fail");
        }
    }
}
