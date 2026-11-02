package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

/** Host-only compiler-table proof; NOT guest type_of or Native Image AOT. */
final class AotReflectionTableV1Test {
    @Test
    void compilerOwnedMetadataIsDeterministicBoundedAndPrivateFieldFree() throws Exception {
        AotReflectionTableV1Smoke.main(new String[0]);
    }
}
