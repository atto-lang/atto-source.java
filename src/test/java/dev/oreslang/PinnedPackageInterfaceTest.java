package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCodec.Entry;
import dev.oreslang.compiler.ExportInterfaceCodec.Header;
import dev.oreslang.compiler.ExportInterfaceCodec.Kind;
import dev.oreslang.compiler.PinnedPackageInterface;
import dev.oreslang.compiler.PinnedPackageInterface.Lock;
import dev.oreslang.compiler.PinnedPackageInterface.NamespacePin;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL Java .oresi codec and compiler-metadata admission.
 * No .atto guest, external library linker, or native-image execution occurs.
 */
final class PinnedPackageInterfaceTest {
    private static final String INSTANCE = "acme/math@1#"
            + "a".repeat(64);
    private static final String COMPILER = "atto-java-1";
    private static final String TARGET = "java-aot-v1";
    private static final String POLICY = "ownership-strict-v1";

    private static ExportInterfaceCodec.InterfaceFile iface(String ns, String module) {
        return ExportInterfaceCodec.encode(new Header(ns, COMPILER, TARGET, POLICY),
                List.of(
                        new Entry(module, Kind.MODULE, module, ""),
                        new Entry(module, Kind.FUNCTION, "add", "(int a,int b):int")));
    }

    private static Lock lock(ExportInterfaceCodec.InterfaceFile... files) {
        List<NamespacePin> pins = Arrays.stream(files).map(f ->
                new NamespacePin(f.header().namespace(), f.sha256())).toList();
        return new Lock("math", INSTANCE, COMPILER, TARGET, POLICY, pins);
    }

    @Test void fixedCrossToolchainOresiWireDigest() {
        var encoded = iface("math", "algebra");
        assertEquals("8db5681e1e41c389e2358f9e0cd5c46e78b5b33519ed4769056268a240847f3d",
                encoded.sha256());
    }

    @Test void resolvesPinnedPublicModuleAndNamespaceAcrossEnvelopes() {
        var geometry = iface("geometry", "vector");
        var arithmetic = iface("arithmetic", "scalar");
        var result = PinnedPackageInterface.admit(lock(geometry, arithmetic),
                Map.of("geometry", geometry.bytes(), "arithmetic", arithmetic.bytes()));
        assertEquals("math", result.alias());
        assertEquals(INSTANCE, result.packageInstanceId());
        assertEquals(4, result.packageExports().size());
        assertEquals(2, result.namespaceExports("arithmetic").size());
        var exported = result.moduleExports("arithmetic", "scalar");
        assertEquals(2, exported.size());
        assertTrue(exported.stream().anyMatch(s ->
                s.kind() == Kind.FUNCTION && s.name().equals("add")));
        assertTrue(result.packageExports().stream().allMatch(s ->
                s.packageInstanceId().equals(INSTANCE)));
        assertThrows(IllegalArgumentException.class, () -> result.moduleExports("arithmetic", "vector"));
        assertThrows(IllegalArgumentException.class, () -> result.namespaceExports("ambient"));
        assertThrows(UnsupportedOperationException.class, () -> result.packageExports().clear());
    }

    @Test void deniesUnpinnedAndSwappedNamespaceFiles() {
        var first = iface("first", "one");
        var second = iface("second", "two");
        var pin = lock(first, second);
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(pin, Map.of("first", first.bytes())));
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(pin,
                        Map.of("first", second.bytes(), "second", first.bytes())));
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(first),
                        Map.of("first", first.bytes(), "ambient", second.bytes())));
    }

    @Test void rejectsStaleHashAndNoncanonicalOrCorruptedBinary() {
        var original = iface("math", "algebra");
        byte[] corrupted = original.bytes();
        corrupted[corrupted.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(original), Map.of("math", corrupted)));
        Lock stale = new Lock("math", INSTANCE, COMPILER, TARGET, POLICY,
                List.of(new NamespacePin("math", "f".repeat(64))));
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(stale, Map.of("math", original.bytes())));
        byte[] extra = Arrays.copyOf(original.bytes(), original.bytes().length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(original), Map.of("math", extra)));
    }

    @Test void rejectsWrongBackendCompilerPolicyAndNamespaceProfile() {
        var original = iface("math", "algebra");
        for (Lock wrong : List.of(
                new Lock("math", INSTANCE, COMPILER, "llvm-v1", POLICY, lock(original).namespaces()),
                new Lock("math", INSTANCE, "different-compiler", TARGET, POLICY, lock(original).namespaces()),
                new Lock("math", INSTANCE, COMPILER, TARGET, "unsafe", lock(original).namespaces()))) {
            assertThrows(IllegalArgumentException.class, () ->
                    PinnedPackageInterface.admit(wrong, Map.of("math", original.bytes())));
        }
    }

    @Test void rejectsDuplicateAndMalformedDependencyLocks() {
        var original = iface("math", "algebra");
        var ns = new NamespacePin("math", original.sha256());
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("math", INSTANCE, COMPILER, TARGET, POLICY, List.of(ns, ns)));
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("bad/alias", INSTANCE, COMPILER, TARGET, POLICY, List.of(ns)));
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("mathA", INSTANCE, COMPILER, TARGET, POLICY, List.of(ns)));
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("_math", INSTANCE, COMPILER, TARGET, POLICY, List.of(ns)));
        assertThrows(IllegalArgumentException.class, () ->
                new Lock("math", "../outside", COMPILER, TARGET, POLICY, List.of(ns)));
        assertThrows(IllegalArgumentException.class, () ->
                new NamespacePin("../cross_package", original.sha256()));
        assertThrows(IllegalArgumentException.class, () ->
                new NamespacePin("math", original.sha256().toUpperCase()));
    }

    @Test void rejectsOrphanFunctionsWithoutExportedContainingModule() {
        var binary = ExportInterfaceCodec.encode(new Header("math", COMPILER, TARGET, POLICY),
                List.of(new Entry("private_tools", Kind.FUNCTION, "leak", "()int")));
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(binary), Map.of("math", binary.bytes())));
    }

    @Test void rejectsSpoofedModuleMarkerAndDoesNotReopenSource() {
        var invalid = ExportInterfaceCodec.encode(new Header("math", COMPILER, TARGET, POLICY),
                List.of(new Entry("private_tools", Kind.MODULE, "public_tools", "")));
        assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(invalid), Map.of("math", invalid.bytes())));
        var original = iface("math", "algebra");
        byte[] mutable = original.bytes();
        var result = PinnedPackageInterface.admit(lock(original), Map.of("math", mutable));
        Arrays.fill(mutable, (byte) 0);
        assertEquals(2, result.namespaceExports("math").size());
    }

    @Test void acceptsEstablishedShaColonPackageIdentity() {
        var original = iface("math", "algebra");
        Lock established = new Lock("math", "foo@sha:" + "1".repeat(64),
                COMPILER, TARGET, POLICY, lock(original).namespaces());
        assertEquals(established.packageInstanceId(),
                PinnedPackageInterface.admit(established,
                        Map.of("math", original.bytes())).packageInstanceId());
    }

    @Test void rejectsSharedModelReservedAliasAndNamespaceKeys() {
        var original = iface("math", "algebra");
        var pin = new NamespacePin("math", original.sha256());
        for (String reserved : List.of("constructor", "prototype", "__proto__", "__root__")) {
            assertThrows(IllegalArgumentException.class, () ->
                    new NamespacePin(reserved, original.sha256()));
            assertThrows(IllegalArgumentException.class, () ->
                    new Lock(reserved, INSTANCE, COMPILER, TARGET, POLICY, List.of(pin)));
        }
    }

    @Test void deniesOversizedInterfaceBeforeCloningOrHashing() {
        var valid = iface("math", "algebra");
        byte[] giant = new byte[16 * 1024 * 1024 + 1];
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                PinnedPackageInterface.admit(lock(valid), Map.of("math", giant)));
        assertTrue(ex.getMessage().contains("package interface byte budget"));
    }

    @Test void rejectsImpossibleOresiEntryCountBeforeAllocation() {
        byte[] forged = iface("math", "algebra").bytes();
        int cursor = 7; // ORESI NUL v1 magic
        for (int i = 0; i < 4; i++) {
            int size = ByteBuffer.wrap(forged, cursor, 4).getInt();
            cursor += 4 + size;
        }
        ByteBuffer.wrap(forged, cursor, 4).putInt(100000);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ExportInterfaceCodec.decode(forged));
        assertTrue(ex.getMessage().contains("impossible export count"));
    }

    @Test void rejectsMaliciousPublicSymbolNamesEvenWithMatchingDigest() {
        for (String unsafe : List.of("bad/../escape", "bad\0name", "bad" + Character.toString(0x202e) + "name")) {
            var forged = ExportInterfaceCodec.encode(
                    new Header("math", COMPILER, TARGET, POLICY),
                    List.of(new Entry("algebra", Kind.MODULE, "algebra", ""),
                            new Entry("algebra", Kind.FUNCTION, unsafe, "()int")));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    PinnedPackageInterface.admit(lock(forged),
                            Map.of("math", forged.bytes())));
            assertTrue(ex.getMessage().contains("unsafe public interface symbol name"));
        }
    }

    @Test void preservesUnicodePublicIdentifiersAndFrozenWireBytes() {
        var encoded = ExportInterfaceCodec.encode(
                new Header("math", COMPILER, TARGET, POLICY),
                List.of(new Entry("algebra", Kind.MODULE, "algebra", ""),
                        new Entry("algebra", Kind.FUNCTION, "sum_\u03bb",
                                "(int a,int b):int")));
        assertEquals("b163a6b76ab78ae943efe70e3d28a270a35417b891fe6f6de7208625709cc0d2",
                encoded.sha256());
        assertEquals(2, PinnedPackageInterface.admit(lock(encoded),
                Map.of("math", encoded.bytes())).namespaceExports("math").size());
    }

    @Test void distinctLockedPackageInstancesNeverCollapseByAlias() {
        var original = iface("math", "algebra");
        Lock a = lock(original);
        Lock b = new Lock(a.alias(), "acme/math@1#" + "b".repeat(64),
                COMPILER, TARGET, POLICY, a.namespaces());
        var left = PinnedPackageInterface.admit(a, Map.of("math", original.bytes()));
        var right = PinnedPackageInterface.admit(b, Map.of("math", original.bytes()));
        assertNotEquals(left.packageExports().get(0).packageInstanceId(),
                right.packageExports().get(0).packageInstanceId());
    }
}
