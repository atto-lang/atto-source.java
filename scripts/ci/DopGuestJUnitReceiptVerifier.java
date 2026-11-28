import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Exact two-guest-case, source-local JUnit evidence gate. Never verifies codegen by itself. */
public final class DopGuestJUnitReceiptVerifier {
    private static final String SUITE = "dev.oreslang.DopScalarGuestParityTest";
    private static final Set<String> METHODS = Set.of(
            "identicalScalarSoaSourceCompilesAndReturnsZeroFromRealJitGuest",
            "incorrectGuestChecksumIsDetectedByActualCompiledExecution");
    private static final int MAX_BYTES = 1024 * 1024;
    private DopGuestJUnitReceiptVerifier() { }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    private static byte[] readBounded(Path file) throws Exception {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size() > 0 && before.size() <= MAX_BYTES,
                "report-not-regular-or-out-of-budget");
        byte[] buf = new byte[MAX_BYTES + 1];
        int used = 0;
        Set<OpenOption> opts = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(file, opts)) {
            while (used < buf.length) {
                int count = channel.read(ByteBuffer.wrap(buf, used, buf.length - used));
                if (count < 0) break;
                require(count != 0, "report-read-stalled");
                used += count;
            }
        }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(used > 0 && used <= MAX_BYTES && used == before.size() && used == after.size(),
                "report-size-changed");
        require(after.isRegularFile() && before.lastModifiedTime().equals(after.lastModifiedTime())
                        && before.fileKey() != null && before.fileKey().equals(after.fileKey()),
                "report-file-swapped");
        return java.util.Arrays.copyOf(buf, used);
    }

    private static int attr(Element root, String key) {
        try { return Integer.parseInt(root.getAttribute(key)); }
        catch (NumberFormatException e) { throw new IllegalStateException("invalid-junit-attribute-" + key); }
    }

    private static void verify(String head, Path reports) throws Exception {
        require(head.matches("[0-9a-f]{40}"), "invalid-head");
        require(Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS), "invalid-report-directory");
        byte[] xml = readBounded(reports.resolve("TEST-" + SUITE + ".xml"));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        var builder = f.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new IllegalStateException("external-entity"); });
        var root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        require(root != null && "testsuite".equals(root.getTagName()) && SUITE.equals(root.getAttribute("name")),
                "wrong-guest-suite");
        require(attr(root, "tests") == 2 && attr(root, "errors") == 0
                && attr(root, "failures") == 0 && attr(root, "skipped") == 0,
                "nonpassing-two-case-report");
        var cases = root.getElementsByTagName("testcase");
        require(cases.getLength() == 2, "incorrect-testcase-count");
        var names = new HashSet<String>();
        for (int i = 0; i < cases.getLength(); i++) {
            var c = (Element) cases.item(i);
            require(c.getParentNode() == root && SUITE.equals(c.getAttribute("classname"))
                            && METHODS.contains(c.getAttribute("name"))
                            && names.add(c.getAttribute("name")), "incorrect-or-duplicate-testcase");
            for (String forbidden : new String[]{"failure", "error", "skipped"}) {
                require(c.getElementsByTagName(forbidden).getLength() == 0, "nonpassing-testcase");
            }
        }
        require(names.equals(METHODS), "missing-required-case");
    }

    private static String document(String first, String second) {
        return "<testsuite name=\"" + SUITE + "\" tests=\"2\" errors=\"0\" failures=\"0\" skipped=\"0\">"
                + "<testcase classname=\"" + SUITE + "\" name=\"" + first + "\"/>"
                + "<testcase classname=\"" + SUITE + "\" name=\"" + second + "\"/>"
                + "</testsuite>";
    }

    private static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("atto-dop-junit-");
        Path file = dir.resolve("TEST-" + SUITE + ".xml");
        String head = "a".repeat(40);
        String first = "identicalScalarSoaSourceCompilesAndReturnsZeroFromRealJitGuest";
        String second = "incorrectGuestChecksumIsDetectedByActualCompiledExecution";
        try {
            String good = document(first, second);
            Files.writeString(file, good);
            verify(head, dir);
            System.out.println("PASS two-case-control");
            String[] mutations = {
                    document(first, first), document(first, "unknown"),
                    good.replace("skipped=\"0\"", "skipped=\"1\""),
                    good.replace("failures=\"0\"", "failures=\"1\""),
                    good.replace("name=\"" + second, "name=\"" + first),
                    good.replace("</testsuite>", "<testcase classname=\"" + SUITE + "\" name=\"extra\"/></testsuite>"),
                    good.replace("<testcase", "<testcase").replace("</testsuite>", "<!DOCTYPE x></testsuite>"),
                    "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + good,
                    good + " ".repeat(MAX_BYTES), "null"
            };
            for (int i = 0; i < mutations.length; i++) {
                Files.writeString(file, mutations[i]);
                boolean denied = false;
                try { verify(head, dir); } catch (Exception expected) { denied = true; }
                require(denied, "bad-report-was-accepted-" + i);
                System.out.println("PASS rejected-negative-" + i);
            }
            Files.writeString(file, good);
            boolean badHead = false;
            try { verify("malformed", dir); } catch (Exception expected) { badHead = true; }
            require(badHead, "bad-head-accepted");
            System.out.println("PASS rejected-bad-head");
            System.out.println("DOP_JUNIT_RECEIPT_SELF_TEST_PASS");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        require(args.length == 2, "usage-head-and-surefire-directory");
        verify(args[0], Path.of(args[1]));
        System.out.println("DOP_JAVA_GUEST_JUNIT_TWO_CASES_PASS exact_head=" + args[0]);
    }
}
