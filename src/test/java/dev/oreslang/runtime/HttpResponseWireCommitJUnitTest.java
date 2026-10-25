package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercise live HTTP/1.1 cancellation on both sides of header commitment. */
class HttpResponseWireCommitJUnitTest {
    @Test @Timeout(35)
    void cancellationBeforeHeadersAndAfterHeadersHasOneCommitPath() throws Exception {
        HttpResponseWireCommitCheck.main(new String[0]);
    }
}
