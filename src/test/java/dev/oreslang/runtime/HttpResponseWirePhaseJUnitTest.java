package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercise the actual NativeHttp.State header transition helper in Maven. */
class HttpResponseWirePhaseJUnitTest {
    @Test @Timeout(25)
    void abortCannotRaceAnAdditionalStatusOrSuccessfulFinish() throws Exception {
        HttpResponseWirePhaseCheck.main(new String[0]);
    }
}
