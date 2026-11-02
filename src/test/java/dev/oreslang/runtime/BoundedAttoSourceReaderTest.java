package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.MalformedInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-reader boundary tests, independent of a full guest runtime. */
final class BoundedAttoSourceReaderTest {
    @TempDir Path temp;

    private void symlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not available");
        }
    }

    @Test
    void directAttoSourceIsAdmitted() throws Exception {
        Path source = temp.resolve("direct.atto");
        Files.writeString(source, "pub fnc main(): void { return; }");
        assertEquals(Files.size(source), BoundedSourceReader.readWithByteCount(source).byteCount());
        assertTrue(BoundedSourceReader.readUtf8(source).contains("main"));
    }

    @Test
    void symlinkedAttoLeafIsRejected() throws Exception {
        Path real = temp.resolve("real.atto");
        Files.writeString(real, "pub fnc main(): void { return; }");
        Path alias = temp.resolve("alias.atto");
        symlinkOrSkip(alias, real.getFileName());
        SecurityException denied = assertThrows(SecurityException.class,
                () -> BoundedSourceReader.readUtf8(alias));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }

    @Test
    void symlinkedAttoParentIsRejected() throws Exception {
        Path real = temp.resolve("actual");
        Files.createDirectories(real);
        Files.writeString(real.resolve("program.atto"), "pub fnc main(): void { return; }");
        Path alias = temp.resolve("linked");
        symlinkOrSkip(alias, real.getFileName());
        SecurityException denied = assertThrows(SecurityException.class,
                () -> BoundedSourceReader.readUtf8(alias.resolve("program.atto")));
        assertTrue(denied.getMessage().contains("symlinked .atto"));
    }

    @Test
    void uppercaseAttoSuffixCannotBypassSymlinkPolicy() throws Exception {
        Path real = temp.resolve("real.ATTO");
        Files.writeString(real, "pub fnc main(): void { return; }");
        Path alias = temp.resolve("alias.ATTO");
        symlinkOrSkip(alias, real.getFileName());
        assertThrows(SecurityException.class, () -> BoundedSourceReader.readUtf8(alias));
    }

    @Test
    void legacyOresSymlinkBehaviorIsUnchanged() throws Exception {
        Path real = temp.resolve("legacy-real.ores");
        Files.writeString(real, "pub fnc main(): void { return; }");
        Path alias = temp.resolve("legacy-link.ores");
        symlinkOrSkip(alias, real.getFileName());
        assertEquals(BoundedSourceReader.readUtf8(real), BoundedSourceReader.readUtf8(alias));
    }

    @Test
    void invalidUtf8StillFailsBeforeParsing() throws Exception {
        Path malformed = temp.resolve("bad.atto");
        Files.write(malformed, new byte[] {(byte) 0xC0, (byte) 0xAF});
        assertThrows(MalformedInputException.class,
                () -> BoundedSourceReader.readUtf8(malformed));
    }
}
