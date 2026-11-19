package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes checked-in .atto source through the actual Java/Truffle guest.
 * This tests guest JIT/interpreter execution, NOT Graal Native Image AOT.
 */
final class OwnershipBangGuestTest {
    private static final Path ROOT =
            Path.of("src/test/resources/atto/ownership-bang-v1");
    private static final Map<String, String> PINS = Map.of(
            "compact", "5336fa679a7816dbd353be4480f95dac639b6af7",
            "spaced", "06afd973b34a6cd09d1d78cee258134b1c3b0451",
            "legacy", "fa3d3ef8173d046783efdae808bd291be00891be",
            "mut-canonical", "a518f08a495763f349c7ab21baa5d61d0cfc7769",
            "mut-compact", "a7e3b55ebd128a671cdb48ede3557aee6dc1b2b0",
            "mut-rt-tail", "b1e982f0ca85291a5e9924e3c6ff571ba13efebe");

    private static String shaOfGitBlob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void ownershipPrefixesAndMutableBorrowAliasesExecuteAttoGuest() throws Exception {
        // These are actual Java/Truffle guests, not parser-only source checks.
        // Borrow aliases must preserve exclusive access to the same Item.
        for (String variant : new String[]{"compact", "spaced", "legacy",
                "mut-canonical", "mut-compact", "mut-rt-tail"}) {
            Path file = ROOT.resolve(variant + ".atto");
            assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), variant);
            // Regular-file checks on the leaf are insufficient if any parent
            // component is a symlink into a different source tree.
            for (Path part = file; part != null; part = part.getParent()) {
                assertFalse(Files.isSymbolicLink(part),
                        "fixture path escaped via symlink: " + part);
            }
            long size = Files.size(file);
            assertTrue(size <= 4096 && size > 20, "bounded .atto fixture: " + variant);
            // Files.readAllBytes would allocate beyond the preflight budget if
            // a file grows after Files.size. Open the leaf without following a
            // symlink and cap the actual read at budget + 1 for drift detection.
            byte[] bytes;
            try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(4097);
            }
            assertTrue(bytes.length <= 4096 && bytes.length > 20,
                    "fixture exceeded read budget: " + variant);
            assertEquals(size, bytes.length, "fixture size changed during read: " + variant);
            assertEquals(PINS.get(variant), shaOfGitBlob(bytes),
                    "reviewed Git blob drift for " + variant);
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertNotNull(OresCompiler.parseAndTypeCheck(text),
                    "compiler AST/ownership admission missing for " + variant);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Source guest = Source.newBuilder(OresLanguage.ID, text,
                    "ownership-bang-" + variant + ".atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(guest);
            }
            assertEquals("42", output.toString(StandardCharsets.UTF_8)
                    .replace("\r\n", "\n"),
                    "actual .atto guest execution " + variant);
        }
    }
}
