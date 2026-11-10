package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs actual production Parser -> checked singleton -> runtime publication.
 * This is NOT a compiled actor guest test or GraalVM Native Image evidence.
 */
final class SingletonSnapshotCompilerV1Test {
    @Test
    @Timeout(30)
    void compileCheckedImmutableSingletonSourceIntoCanonicalDomain() throws Exception {
        SingletonSnapshotCompilerV1Smoke.main(new String[0]);
    }
}
