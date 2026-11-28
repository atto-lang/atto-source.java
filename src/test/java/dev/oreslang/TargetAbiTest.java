package dev.oreslang;

import dev.oreslang.compiler.TargetAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TargetAbiTest {
    @Test void profilesMatchVersionedCrossBackendTable() throws Exception {
        var lines = Files.readAllLines(Path.of("contracts/architecture/v1/targets.tsv"));
        assertEquals("triple\tpointer_bits\tendian\tabi\temission", lines.get(0));
        var names = new HashSet<String>();
        assertEquals(6, lines.size());
        for (int i = 1; i < lines.size(); ++i) {
            String[] row = lines.get(i).split("\\t", -1);
            assertEquals(5, row.length);
            assertTrue(names.add(row[0]));
            var target = TargetAbi.parse(row[0]);
            assertEquals(Integer.parseInt(row[1]), target.pointerBits());
            assertEquals(row[2], target.endian());
            assertEquals(row[3], target.abi());
            assertEquals(row[4], target.emission());
            assertEquals(row[4].equals("experimental-wasm32-ir"), target.hasExperimentalWasmIr());
        }
    }

    @Test void rejectForgedAndUnknownTargetNames() {
        for (var triple : new String[] {"", "HOST", "arm64", "x86_64-apple-darwin",
                                         "wasm64-unknown-unknown", "aarch64-unknown-linux-musl"}) {
            assertThrows(IllegalArgumentException.class, () -> TargetAbi.parse(triple));
        }
        assertThrows(NullPointerException.class, () -> TargetAbi.parse(null));
    }

    @Test void thirtyTwoBitAddressingCannotSilentlyTruncate() {
        for (var name : new String[] {"i686-unknown-linux-gnu", "armv7-unknown-linux-gnueabihf",
                                      "wasm32-unknown-unknown"}) {
            var target = TargetAbi.parse(name);
            assertEquals(Integer.MAX_VALUE, target.checkedContiguousBytes(Integer.MAX_VALUE));
            assertThrows(ArithmeticException.class, () -> target.checkedContiguousBytes(1L << 31));
            assertThrows(ArithmeticException.class, () -> target.checkedContiguousBytes(-1));
            assertEquals(Integer.MAX_VALUE, target.checkedJavaByteBufferBytes(Integer.MAX_VALUE));
            assertThrows(ArithmeticException.class,
                    () -> target.checkedJavaByteBufferBytes(1L << 31));
        }
        var wide = TargetAbi.parse("aarch64-unknown-linux-gnu");
        assertEquals(1L << 33, wide.checkedContiguousBytes(1L << 33));
        assertEquals(Long.MAX_VALUE, wide.checkedContiguousBytes(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> wide.checkedContiguousBytes(-1));
        assertThrows(ArithmeticException.class, () -> wide.checkedJavaByteBufferBytes(1L << 33));
        assertThrows(ArithmeticException.class, () -> wide.checkedJavaByteBufferBytes(1L << 31));
        assertEquals(Integer.MAX_VALUE, wide.checkedJavaByteBufferBytes(Integer.MAX_VALUE));
    }
}
