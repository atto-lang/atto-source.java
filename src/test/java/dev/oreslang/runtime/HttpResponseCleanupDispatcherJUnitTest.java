package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Ensure shutdown cannot strand HTTP transport cleanup or admission. */
class HttpResponseCleanupDispatcherJUnitTest {
    @Test @Timeout(20)
    void rejectedIoExecutorStillClosesTransportAndReleasesAdmission() throws Exception {
        HttpResponseCleanupDispatcherCheck.main(new String[0]);
    }
}
