package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ_WRITE)
final class NioOnlyPolicyTest {
    @Test
    void policyParsingIsExplicitAndUnknownModesFailClosed() {
        assertEquals(
                NioOnlyPolicy.Mode.LEGACY_COMPAT,
                NioOnlyPolicy.parse(null));
        assertEquals(
                NioOnlyPolicy.Mode.LEGACY_COMPAT,
                NioOnlyPolicy.parse("legacy-compat"));
        assertEquals(
                NioOnlyPolicy.Mode.NIO_ONLY,
                NioOnlyPolicy.parse("nio-only"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NioOnlyPolicy.parse("fallback"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NioOnlyPolicy.parse("blocking"));
    }

    @Test
    void strictModeNeverStartsVirtualThreadIoWork() throws Exception {
        String previous = System.getProperty(NioOnlyPolicy.PROPERTY);
        try {
            System.setProperty(NioOnlyPolicy.PROPERTY, "nio-only");
            AtomicBoolean invoked = new AtomicBoolean();
            try (AsyncNativeIo io = new AsyncNativeIo(2)) {
                OresFuture<Integer> operation =
                        io.submit("socket.read_async", () -> {
                            invoked.set(true);
                            return 42;
                        });
                ExecutionException failed = assertThrows(
                        ExecutionException.class,
                        () -> operation.get(2, TimeUnit.SECONDS));
                assertInstanceOf(
                        UnsupportedOperationException.class,
                        failed.getCause());
                assertFalse(
                        invoked.get(),
                        "legacy I/O body must not be executed");
            }
        } finally {
            if (previous == null) {
                System.clearProperty(NioOnlyPolicy.PROPERTY);
            } else {
                System.setProperty(NioOnlyPolicy.PROPERTY, previous);
            }
        }
    }

    @Test
    void strictModeRejectsLegacyBlockingNetworkConnectBeforeContextUse() {
        String previous = System.getProperty(NioOnlyPolicy.PROPERTY);
        try {
            System.setProperty(NioOnlyPolicy.PROPERTY, "nio-only");
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeIo.connect(null, "127.0.0.1", 9));
            assertTrue(failure.getMessage().contains("network.connect"));
        } finally {
            if (previous == null) {
                System.clearProperty(NioOnlyPolicy.PROPERTY);
            } else {
                System.setProperty(NioOnlyPolicy.PROPERTY, previous);
            }
        }
    }

    @Test
    void strictModeRejectsSynchronousHttpBeforeContextOrTransportUse() {
        String previous = System.getProperty(NioOnlyPolicy.PROPERTY);
        try {
            System.setProperty(NioOnlyPolicy.PROPERTY, "nio-only");
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> NativeIo.httpGetText(
                            null, "http://127.0.0.1/"));
            assertTrue(failure.getMessage().contains("http.get"));
        } finally {
            if (previous == null) {
                System.clearProperty(NioOnlyPolicy.PROPERTY);
            } else {
                System.setProperty(NioOnlyPolicy.PROPERTY, previous);
            }
        }
    }

}
