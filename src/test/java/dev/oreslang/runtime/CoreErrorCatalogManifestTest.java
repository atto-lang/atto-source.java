package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Main-branch host-only parity gate. Does not require the still-conflicted
 * guest std/error parser/evaluator imports from the stacked source PR.
 */
final class CoreErrorCatalogManifestTest {
    private static final Pattern ROW = Pattern.compile(
            "\"name\"\\s*:\\s*\"([A-Za-z]\\w*)\"\\s*,\\s*\"parent\"\\s*:\\s*(null|\"[A-Za-z]\\w*\")"
                    + "\\s*,\\s*\"code\"\\s*:\\s*(\\d+)\\s*,\\s*\"abstract\"\\s*:\\s*(true|false)"
                    + "\\s*,\\s*\"interfaces\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern INTERFACE = Pattern.compile("\"([A-Za-z]\\w*)\"");

    private record Entry(String parent, int code, boolean abstractType, Set<String> interfaces) { }

    @Test
    void everyRuntimeDescriptorExactlyMatchesTheV1Manifest() throws Exception {
        String manifest = Files.readString(Path.of("contracts/v1/error-catalog.json"));
        assertTrue(manifest.contains("\"schemaVersion\": 1"));
        assertTrue(manifest.contains("\"package\": \"std/error\""));

        Map<String, Entry> entries = new HashMap<>();
        Set<Integer> codes = new HashSet<>();
        Matcher rows = ROW.matcher(manifest);
        while (rows.find()) {
            String name = rows.group(1);
            String parentName = rows.group(2);
            String parent = "null".equals(parentName)
                    ? null : parentName.substring(1, parentName.length() - 1);
            int code = Integer.parseInt(rows.group(3));
            boolean abstractType = Boolean.parseBoolean(rows.group(4));

            Set<String> interfaces = new HashSet<>();
            String rawInterfaces = rows.group(5);
            Matcher interfaceNames = INTERFACE.matcher(rawInterfaces);
            while (interfaceNames.find()) {
                String iface = interfaceNames.group(1);
                assertTrue(Set.of("CodedError", "Serializable", "RetryAdvisory", "ActorApiError")
                        .contains(iface), "unknown nominal interface: " + iface);
                assertTrue(interfaces.add(iface), "duplicate interface: " + iface);
            }
            assertTrue(rawInterfaces.replaceAll("\"[A-Za-z]\\w*\"|[\\s,]", "").isEmpty(),
                    "malformed interface list: " + name);
            assertNull(entries.put(name, new Entry(parent, code, abstractType, Set.copyOf(interfaces))),
                    "duplicate core type name: " + name);
            assertTrue(codes.add(code), "duplicate core code: " + code);
        }
        assertEquals(99, entries.size(), "std/error v1 manifest entries");
        assertEquals(99, CoreErrorDescriptors.size(), "runtime catalog entries");

        for (var row : entries.entrySet()) {
            String name = row.getKey();
            Entry expected = row.getValue();
            var descriptor = CoreErrorDescriptors.requireKnown(name);
            assertEquals(name, descriptor.name());
            assertEquals(expected.code(), descriptor.code(), name + " code");
            assertEquals(expected.abstractType(), descriptor.abstractType(), name + " abstract");
            assertSame(descriptor, CoreErrorDescriptors.requireKnownCode(expected.code()));
            assertSame(expected.parent() == null
                            ? null : CoreErrorDescriptors.requireKnown(expected.parent()),
                    descriptor.parent(), name + " parent");

            for (var iface : CoreErrorDescriptors.CoreInterface.values()) {
                String expectedName = switch (iface) {
                    case CODED_ERROR -> "CodedError";
                    case SERIALIZABLE -> "Serializable";
                    case RETRY_ADVISORY -> "RetryAdvisory";
                    case ACTOR_API_ERROR -> "ActorApiError";
                };
                String cursor = name;
                boolean nominalMember = false;
                Set<String> visited = new HashSet<>();
                while (cursor != null) {
                    assertTrue(visited.add(cursor), "cycle at " + name);
                    Entry ancestor = entries.get(cursor);
                    assertNotNull(ancestor, "missing catalog ancestor " + cursor);
                    if (ancestor.interfaces().contains(expectedName)) {
                        nominalMember = true;
                        break;
                    }
                    cursor = ancestor.parent();
                }
                assertEquals(nominalMember, descriptor.implementsInterface(iface),
                        name + " implements " + expectedName);
            }
        }
    }
}
