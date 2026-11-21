package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 32-bit actor slab heap: same words as the LLVM (C++), rs/wasm32 and .NET heaps.
 * The golden fixture is shared byte-for-byte across those repositories.
 */
final class ActorSlabHeapTest {
    private static final ActorSlabHeap.Status OK = ActorSlabHeap.Status.OK;

    @Test
    void allocateZeroedResolveAndDoubleFree() {
        try (ActorSlabHeap h = new ActorSlabHeap(1 << 20, 1 << 20)) {
            assertEquals(0, h.committedBytes(), "commits nothing up front");
            var a = h.allocate(24, 8);
            assertTrue(a.ok());
            byte[] buf = new byte[32];
            assertEquals(OK, h.read(a.word(), buf, 24));
            for (int i = 0; i < 24; i++) assertEquals(0, buf[i]);
            assertEquals(OK, h.read(a.word(), buf, 32), "class rounding");
            assertEquals(ActorSlabHeap.Status.INVALID_SIZE, h.read(a.word(), new byte[64], 33));
            assertEquals(OK, h.free(a.word()));
            assertEquals(ActorSlabHeap.Status.INVALID_REF, h.free(a.word()), "double free");
            assertEquals(ActorSlabHeap.Status.INVALID_SIZE, h.allocate(0, 8).status());
            assertEquals(ActorSlabHeap.Status.INVALID_ALIGNMENT, h.allocate(8, 3).status());
            assertEquals(ActorSlabHeap.Status.INVALID_ALIGNMENT, h.allocate(8, 128).status());
            for (long align : new long[] {8, 16, 32, 64})
                for (long size : new long[] {1, 17, 50, 100, 700, 2048}) {
                    var r = h.allocate(size, align);
                    assertTrue(r.ok());
                    assertEquals(0, ((Integer.toUnsignedLong(r.word()) - 1) * 8) % align, "alignment");
                    assertEquals(OK, h.free(r.word()));
                }
            assertEquals(0, h.liveObjects());
        }
    }

    @Test
    void packingAndCrossClassPageReuse() {
        try (ActorSlabHeap h = new ActorSlabHeap(1 << 20, 1 << 20)) {
            List<Integer> refs = new ArrayList<>();
            for (int i = 0; i < 253; i++) refs.add(h.allocate(16, 8).word());
            assertEquals(2, h.livePages(), "252 sixteen-byte objects per page");
            long committed = h.committedBytes();
            for (int r : refs) assertEquals(OK, h.free(r));
            assertEquals(0, h.livePages());
            assertEquals(2, h.freePages());
            for (int i = 0; i < 4; i++) assertTrue(h.allocate(1000, 8).ok());
            assertEquals(committed, h.committedBytes(), "freed pages serve another class");
        }
    }

    @Test
    void forgedInteriorAndTailPageRefsFailClosed() {
        try (ActorSlabHeap h = new ActorSlabHeap(1 << 20, 1 << 20)) {
            int a = h.allocate(32, 8).word();
            for (int w : new int[] {a + 1, 1, -1 /* 0xFFFFFFFF */, a + 512})
                assertEquals(ActorSlabHeap.Status.INVALID_REF, h.free(w), "forged word " + Integer.toUnsignedString(w));
            int big = h.allocate(3 * 4096, 8).word();
            byte[] ones = new byte[3 * 4096];
            java.util.Arrays.fill(ones, (byte) 1);
            assertEquals(OK, h.write(big, ones, ones.length));
            assertEquals(ActorSlabHeap.Status.INVALID_REF, h.free(big + 4096 / 8), "tail page is payload, not a header");
            assertEquals(OK, h.free(big));
            assertEquals(OK, h.free(a));
        }
    }

    @Test
    void budgetFailureIsAtomic() {
        try (ActorSlabHeap h = new ActorSlabHeap(1 << 20, 64 * 1024)) {
            List<Integer> refs = new ArrayList<>();
            ActorSlabHeap.Result r;
            while ((r = h.allocate(512, 8)).ok()) refs.add(r.word());
            assertEquals(ActorSlabHeap.Status.BUDGET_EXCEEDED, r.status());
            long objects = h.liveObjects(), pages = h.livePages();
            assertEquals(ActorSlabHeap.Status.BUDGET_EXCEEDED, h.allocate(100_000, 8).status());
            assertEquals(objects, h.liveObjects());
            assertEquals(pages, h.livePages());
            assertEquals(OK, h.free(refs.get(refs.size() - 1)));
            assertTrue(h.allocate(512, 8).ok());
        }
    }

    @Test
    void handlesFifoStaleAndQuarantine() {
        try (ActorSlabHeap h = new ActorSlabHeap(1 << 20, 1 << 20, 64 * 1024, 4, 0)) {
            assertEquals(3, h.handleSlotBits(), "capacity-sized, not a fixed 20/12 split");
            assertEquals((1 << 29) - 1, h.handleMaxGeneration());
            int a = h.allocateHandle(40, 8).word(), b = h.allocateHandle(40, 8).word();
            assertEquals(1, a & 7);
            assertEquals(2, b & 7);
            assertEquals(OK, h.freeHandle(a));
            assertEquals(ActorSlabHeap.Status.STALE_HANDLE, h.resolveHandle(a).status());
            int c = h.allocateHandle(40, 8).word(), d = h.allocateHandle(40, 8).word(), e = h.allocateHandle(40, 8).word();
            assertEquals(3, c & 7);
            assertEquals(4, d & 7);
            assertEquals(1, e & 7, "FIFO: freed slot reused last");
            assertNotEquals(a, e, "new generation");
            assertEquals(ActorSlabHeap.Status.HANDLE_TABLE_FULL, h.allocateHandle(40, 8).status());
        }
        try (ActorSlabHeap small = new ActorSlabHeap(1 << 20, 1 << 20, 64 * 1024, 2, 2)) {
            int n = 0;
            ActorSlabHeap.Result x;
            while ((x = small.allocateHandle(16, 8)).ok()) { n++; assertEquals(OK, small.freeHandle(x.word())); }
            assertEquals(6, n, "3 generations per slot");
            assertEquals(2, small.quarantinedHandles());
            assertTrue(small.allocate(16, 8).ok(), "Offset32 unaffected by quarantine");
        }
        assertThrows(IllegalArgumentException.class, () -> new ActorSlabHeap(1 << 20, 1 << 20, 64 * 1024, 4, 30));
    }

    @Test
    void retireIsTerminal() {
        ActorSlabHeap h = new ActorSlabHeap(1 << 20, 1 << 20);
        int r = h.allocate(100, 8).word();
        h.close();
        assertEquals(ActorSlabHeap.Status.RETIRED, h.allocate(16, 8).status());
        assertEquals(ActorSlabHeap.Status.RETIRED, h.free(r));
        assertEquals(0, h.committedBytes());
    }

    @Test
    void goldenMatchesLlvmRsAndNet() throws Exception {
        Path golden = null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/actor-slab-heap/v1/actor_slab_heap_golden_v1.txt");
            if (Files.isRegularFile(candidate)) { golden = candidate; break; }
        }
        assertNotNull(golden, "golden fixture");
        String[] words = Files.readString(golden).trim().split("\\s+");
        List<Integer> got = ActorSlabHeap.goldenScriptV1();
        assertEquals(words.length, got.size(), "golden length");
        for (int i = 0; i < words.length; i++)
            assertEquals(Long.parseLong(words[i]), Integer.toUnsignedLong(got.get(i)), "golden word " + i);
    }
}
