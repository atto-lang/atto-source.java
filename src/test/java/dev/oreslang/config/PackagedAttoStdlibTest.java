package dev.oreslang.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Guards AttoLang stdlib inclusion in JVM classpath and native-image build inputs. */
final class PackagedAttoStdlibTest {
    @Test
    void replayStdlibAttoSourceIsPackagedAsAResource() throws Exception {
        String path = "dev/oreslang/std/replay_stream.atto";
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "AttoLang standard library resource is missing: " + path);
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("pub define class ReplayWindow"),
                    "Packaged AttoLang replay source must contain ReplayWindow");
        }
    }
}
