package dev.atto.runtime;

import org.junit.jupiter.api.Test;

final class GenerationalReplyRegistryJUnitTest {
    @Test void runtimeOwnedTypedReplySlabConformance() throws Exception {
        GenerationalReplyRegistryCheck.main(new String[0]);
    }
}
