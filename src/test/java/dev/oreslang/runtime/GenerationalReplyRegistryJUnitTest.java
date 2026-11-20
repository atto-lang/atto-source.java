package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

final class GenerationalReplyRegistryJUnitTest {
    @Test void runtimeOwnedTypedReplySlabConformance() throws Exception {
        GenerationalReplyRegistryCheck.main(new String[0]);
    }
}
