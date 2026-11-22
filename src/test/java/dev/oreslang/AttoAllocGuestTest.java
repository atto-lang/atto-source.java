package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real linked guest tests: compile native Atto library source, run guest, and
 * require a terminal 42 witness. These use relative source imports only;
 * package-name "atto.alloc/..." admission requires a separate resolver test.
 * No host-side collection model is accepted as guest execution evidence.
 */
final class AttoAllocGuestTest {
    @TempDir Path temp;
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    private void stageLibrary(String module) throws Exception {
        Path source = ROOT.resolve("stdlib/alloc/" + module + ".atto");
        assertTrue(Files.isRegularFile(source) && !Files.isSymbolicLink(source),
                "library source must be an ordinary file: " + source);
        Files.copy(source, temp.resolve(module + ".atto"));
    }

    private void runPositive(String module) throws Exception {
        stageLibrary(module);
        Path fixture = ROOT.resolve("tests/fixtures/alloc/" + module + "_smoke.atto");
        assertTrue(Files.isRegularFile(fixture) && !Files.isSymbolicLink(fixture));
        String source = Files.readString(fixture, StandardCharsets.UTF_8);
        String originalImport = "\"atto.alloc/" + module + "\"";
        assertTrue(source.contains(originalImport), "missing package-qualified fixture import");
        String ending = "  return 0;\n}";
        assertEquals(1, source.split(java.util.regex.Pattern.quote(ending), -1).length - 1);
        // Local source graph tests independent of package namespace install.
        // A failed early return does not emit 42, preventing false-green guests.
        source = source.replace(originalImport, "\"./" + module + "\"")
                .replace(ending, "  stdio.stdout.write(42);\n  return 0;\n}");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, source, StandardCharsets.UTF_8);
        assertEquals(2, LinkedProgramRunner.validate(entry, Map.of()).units().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        assertEquals("42", out.toString(StandardCharsets.UTF_8),
                "compiled guest did not reach its success witness: " +
                err.toString(StandardCharsets.UTF_8));
    }

    private void rejectPrivateField(String module, String source) throws Exception {
        stageLibrary(module);
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, source, StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream()));
    }

    @Test void slabGuestRejectsCollisionsAndStaleHandles() throws Exception {
        runPositive("slab");
    }

    @Test void dequeGuestMaintainsRingAndReserveSemantics() throws Exception {
        runPositive("deque");
    }

    @Test void actorHeapGuestRejectsForeignHandlesAndDoubleRelease() throws Exception {
        runPositive("actor_heap");
    }

    @Test void slabPrivateTokenCannotBeReadByImporter() throws Exception {
        rejectPrivateField("slab", """
                import module slab from "./slab";
                pub fnc main(): int {
                  let mut slab.Slab slots = slab.new_slab(17, 1);
                  let slab.Handle h = slots.insert(5).unwrap();
                  let slab.OwnerToken stolen = h.owner_token;
                  return 0;
                }
                """);
    }

    @Test void heapPrivateTokenCannotBeReadByImporter() throws Exception {
        rejectPrivateField("actor_heap", """
                import module actor_heap from "./actor_heap";
                pub fnc main(): int {
                  let mut actor_heap.ActorHeap heap = actor_heap.new_actor_heap(19, 8, 2);
                  let actor_heap.HeapHandle h = heap.allocate(1).unwrap();
                  let actor_heap.OwnerToken stolen = h.owner_token;
                  return 0;
                }
                """);
    }
}
