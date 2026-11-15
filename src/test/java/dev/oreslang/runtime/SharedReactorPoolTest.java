package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class SharedReactorPoolTest {
    @Test @Timeout(30)
    void sharedPollingAndExecutionPreserveBoundedAdmission() throws Exception {
        SharedReactorPoolCheck.main(new String[0]);
    }
}
