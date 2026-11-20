package dev.oreslang.runtime.compute;

import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ComputeProviderRegistryTest {

    private static ComputeCapabilities caps(String backend) {
        return new ComputeCapabilities(
                backend, "test device", 64, 1L << 30, 256L, true, false);
    }

    private static ComputeProvider provider(String backend, String... deviceIds) {
        return new ComputeProvider() {
            @Override
            public String backendId() {
                return backend;
            }

            @Override
            public List<ComputeDevice> devices() {
                return java.util.Arrays.stream(deviceIds)
                        .map(id -> new ComputeDevice(backend, id, caps(backend)))
                        .toList();
            }
        };
    }

    @Test
    void explicitRegistryFindsExactBackendAndDevice() {
        var registry = ComputeProviderRegistry.of(List.of(
                provider("vulkan", "gpu0", "gpu1"),
                provider("metal", "integrated")));

        assertEquals(List.of("vulkan", "metal"), registry.backends());
        assertEquals("gpu1",
                registry.device("vulkan", "gpu1").orElseThrow().deviceId());
        assertTrue(registry.device("cuda", "gpu0").isEmpty(),
                "missing backend must never silently fall back");
    }

    @Test
    void duplicateBackendAndDeviceIdsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ComputeProviderRegistry.of(List.of(
                        provider("vulkan", "gpu0"),
                        provider("vulkan", "gpu1"))));

        ComputeProvider duplicates = new ComputeProvider() {
            @Override
            public String backendId() {
                return "metal";
            }

            @Override
            public List<ComputeDevice> devices() {
                return List.of(
                        new ComputeDevice("metal", "gpu0", caps("metal")),
                        new ComputeDevice("metal", "gpu0", caps("metal")));
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> ComputeProviderRegistry.of(List.of(duplicates)));
    }

    @Test
    void providerCannotSmuggleDeviceFromDifferentBackend() {
        ComputeProvider mismatched = new ComputeProvider() {
            @Override
            public String backendId() {
                return "vulkan";
            }

            @Override
            public List<ComputeDevice> devices() {
                return List.of(new ComputeDevice("metal", "gpu0", caps("metal")));
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> ComputeProviderRegistry.of(List.of(mismatched)));
    }

    @Test
    void oversizedProviderIsRejectedBeforeSnapshotIteration() {
        List<ComputeDevice> oversized = new AbstractList<>() {
            @Override
            public ComputeDevice get(int index) {
                fail("oversized provider list must not be iterated or copied");
                return null;
            }

            @Override
            public int size() {
                return 1025;
            }
        };
        ComputeProvider provider = new ComputeProvider() {
            @Override
            public String backendId() {
                return "vulkan";
            }

            @Override
            public List<ComputeDevice> devices() {
                return oversized;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> ComputeProviderRegistry.of(List.of(provider)));
    }

    @Test
    void metadataRegistryDoesNotRetainProviderAuthority() {
        for (var field : ComputeProviderRegistry.class.getDeclaredFields()) {
            assertFalse(
                    field.getGenericType().getTypeName().contains("ComputeProvider"),
                    "metadata registry must not retain executable provider authority");
        }
    }

    @Test
    void capabilitiesRejectInvalidWidthsAndUnboundedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeCapabilities(
                        "vulkan", "gpu", 48, 1024, 64, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeCapabilities(
                        "vulkan", "gpu", 64, 0, 64, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeCapabilities(
                        "vulkan", "gpu", 64, 1024, 0, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeCapabilities(
                        "vulkan", "gpu", 64, 1024, -1, true, false));
    }
}
