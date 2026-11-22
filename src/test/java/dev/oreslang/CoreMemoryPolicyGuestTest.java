package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.stdlib.CoreLibrarySources;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real compiler/linker/guest probes for the packaged Atto core-policy modules.
 *
 * A passing interpreter/guest test is not a proof of native-image AOT,
 * pointer dereference safety or cross-thread atomic linearizability.
 */
final class CoreMemoryPolicyGuestTest {
    @TempDir Path work;

    @Test
    void closedRegistryLoadsExactBackendOwnedAttoSource() throws Exception {
        for (String name : new String[] {"mem", "ptr", "atomic"}) {
            String canonical = "ores.core/" + name;
            String resource = CoreLibrarySources.oresCoreSource(canonical);
            assertEquals(Files.readString(
                    Path.of("stdlib", "core", name + ".atto"), StandardCharsets.UTF_8),
                    resource, "packaged core resource must match backend-owned source");
            assertEquals(name + ".atto", CoreLibrarySources.oresCoreFileName(canonical));
            assertTrue(CoreLibrarySources.packagedCorePaths().contains(name + ".atto"));
            assertEquals(resource, CoreLibrarySources.oresCoreSource(canonical + ".atto"));
            assertEquals(CoreLibrarySources.oresCoreUnitId(canonical),
                    CoreLibrarySources.oresCoreUnitId(canonical + ".atto"));
            assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(resource)),
                    "real parser/type-check must accept " + canonical);
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource(canonical + ".ores"),
                    "do not grant a new legacy .ores alias");
        }
    }

    private int linkedGuestExit(String code, String fixture) throws Exception {
        Path entry = work.resolve(fixture + ".atto");
        Files.writeString(entry, code, StandardCharsets.UTF_8);
        assertEquals(2, LinkedProgramRunner.validate(entry, Map.of()).units().size(),
                "expected a real imported .atto module, not a local substitute");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var result = LinkedProgramRunner.runWithExitStatus(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        return result.exitStatus();
    }

    @Test
    void linkedMemAttoCalculatesAlignedSize42() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc align_up_checked from "ores.core/mem";
                pub fnc main(): int {
                    return imports::align_up_checked(41, 2);
                }
                """, "mem-aligned"));
    }

    @Test
    void linkedPtrAttoChecksIndexedByteOffset42() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc checked_index_offset from "ores.core/ptr";
                pub fnc main(): int {
                    return imports::checked_index_offset(64, 0, 7, 6, 7, 1, 1);
                }
                """, "ptr-offset"));
    }

    @Test
    void linkedAtomicOrderPolicyRunsNextToExistingAtomicIntGuest() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc default_order from "ores.core/atomic";
                pub fnc main(): int {
                    val cell: Atomic<int> = Atomic.new(38);
                    cell.fetch_add(imports::default_order());
                    return cell.load();
                }
                """, "atomic-order"));
    }

    @Test
    void unknownOrTraversingCoreModuleCannotBecomeAmbientFilesystemImport() throws Exception {
        for (String importPath : new String[] {
                "ores.core/../ptr", "ores.core/atomic/../mem",
                "ores.core/ptr//mem", "ores.core/missing",
                "ores.core/atomic.ores", "ores.core/ptr.ores"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.oresCoreSource(importPath),
                    "closed compiler registry must reject " + importPath);
        }
    }
    @Test
    void linkedMemoryOverflowPolicyRejectsMaxSizeWithoutWrapping() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc align_up_checked from "ores.core/mem";
                pub fnc main(): int {
                    if imports::align_up_checked(9223372036854775807, 8) == -1 then
                        return 42;
                    fi
                    return 1;
                }
                """, "mem-overflow"));
    }

    @Test
    void linkedPointerBoundsRejectOnePastEnd() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc checked_index_offset from "ores.core/ptr";
                pub fnc main(): int {
                    if imports::checked_index_offset(64, 0, 8, 8, 8, 8, 8) == -1 then
                        return 42;
                    fi
                    return 1;
                }
                """, "ptr-bounds"));
    }

    @Test
    void linkedAtomicPolicyRejectsIllegalCasOrders() throws Exception {
        assertEquals(42, linkedGuestExit("""
                import fnc valid_compare_exchange_orders from "ores.core/atomic";
                pub fnc main(): int {
                    if imports::valid_compare_exchange_orders(4, 2) then
                        return 1;
                    fi
                    if imports::valid_compare_exchange_orders(3, 1) then
                        return 42;
                    fi
                    return 2;
                }
                """, "atomic-cas-orders"));
    }

    @Test
    void guestOrderedAtomicsRemainFailClosedUntilSignatureLoweringExists() {
        // The host now admits ordered overloads; that does NOT make the
        // same guest call signature legal until compiler/lowering supports it.
        for (String statements : new String[] {
                "val c: Atomic<int> = Atomic.new(5); c.load(4);",
                "val c: Atomic<int> = Atomic.new(5); c.store(6, 2);",
                "val c: Atomic<int> = Atomic.new(5); c.fetch_add(1, 3);",
                "val c: Atomic<int> = Atomic.new(5); c.compare_exchange(5, 6, 4, 4);"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse(
                            "pub fnc main(): void { " + statements + " return; }")));
        }
    }

}
