package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ_WRITE)
final class NioOnlyPolicyTest {
    @Test void policyParsingIsExplicitAndFailClosedForUnknownModes() {
        assertEquals(NioOnlyPolicy.Mode.LEGACY_COMPAT, NioOnlyPolicy.parse(null));
        assertEquals(NioOnlyPolicy.Mode.LEGACY_COMPAT, NioOnlyPolicy.parse("legacy-compat"));
        assertEquals(NioOnlyPolicy.Mode.NIO_ONLY, NioOnlyPolicy.parse("nio-only"));
        assertThrows(IllegalArgumentException.class, () -> NioOnlyPolicy.parse("fallback"));
        assertThrows(IllegalArgumentException.class, () -> NioOnlyPolicy.parse("blocking"));
    }

    @Test void strictModeNeverStartsVirtualThreadIowork() throws Exception {
        String previous = System.getProperty(NioOnlyPolicy.PROPERTY);
        try {
            System.setProperty(NioOnlyPolicy.PROPERTY, "nio-only");
            var invoked = new AtomicBoolean();
            try (var io = new AsyncNativeIo(2)) {
                var operation = io.submit("socket.read_async", () -> {
                    invoked.set(true);
                    return 42;
                });
                var failed = assertThrows(ExecutionException.class,
                        () -> operation.get(2, TimeUnit.SECONDS));
                assertInstanceOf(UnsupportedOperationException.class, failed.getCause());
                assertFalse(invoked.get(), "legacy I/O body must not be executed");
            }
        } finally {
            if (previous == null) System.clearProperty(NioOnlyPolicy.PROPERTY);
            else System.setProperty(NioOnlyPolicy.PROPERTY, previous);
        }
    }
}
