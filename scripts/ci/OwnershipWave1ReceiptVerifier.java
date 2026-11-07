import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Fail-closed JDK-only structural validator of the ownership Surefire evidence.
 * A synthetic structurally correct XML report is still UNATTESTED.
 * Actual compiler execution requires independent stepful exact-head CI.
 */
public final class OwnershipWave1ReceiptVerifier {
    private static final Map<String, Integer> SUITE_COUNTS = Map.of(
            "dev.oreslang.PointerlessFncOwnershipTest", 41,
            "dev.oreslang.StaticBorrowEscapeBoundaryTest", 27,
            "dev.oreslang.ProjectedMethodReceiverLoanTest", 17,
            "dev.oreslang.PrimitiveBorrowCopyTest", 4,
            "dev.oreslang.SharedBorrowContractTest", 4,
            "dev.oreslang.AttoOwnershipWave1GuestTest", 8,
            "dev.oreslang.TernaryArmTypeSoundnessTest", 2);
    private static final int REQUIRED_CASES = 103;
    private static final long MAX_XML_BYTES = 1_048_576;

    private OwnershipWave1ReceiptVerifier() { }

    private static void need(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Map<String, Set<String>> loadManifest(Path file) throws Exception {
        need(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(file), "missing or symlinked manifest");
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            need(!line.isEmpty() && line.matches("dev\\.oreslang\\.[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*"),
                    "malformed required method entry: " + line);
            String[] pieces = line.split("#", -1);
            need(SUITE_COUNTS.containsKey(pieces[0]), "unrecognized suite in manifest: " + pieces[0]);
            need(expected.computeIfAbsent(pieces[0], unused -> new LinkedHashSet<>()).add(pieces[1]),
                    "duplicate required method: " + line);
        }
        need(expected.keySet().equals(SUITE_COUNTS.keySet()), "missing required suites in manifest");
        int count = 0;
        for (var entry : SUITE_COUNTS.entrySet()) {
            int size = expected.get(entry.getKey()).size();
            need(size == entry.getValue(), "wrong manifest method count for " + entry.getKey());
            count += size;
        }
        need(count == REQUIRED_CASES, "incorrect total required method count");
        return expected;
    }

    private static int attribute(Element element, String key) {
        String value = element.getAttribute(key);
        need(value.matches("0|[1-9][0-9]*"), "missing or invalid JUnit attribute: " + key);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalStateException("overflowing JUnit attribute " + key, ex); }
    }

    private static Element parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new IllegalStateException("external entity is forbidden");
        });
        return builder.parse(path.toFile()).getDocumentElement();
    }

    static int verify(String head, String tree, Path manifest, Path reports) throws Exception {
        need(head.matches("[0-9a-f]{40}") && tree.matches("[0-9a-f]{40}"),
                "invalid exact-head or exact-tree SHA");
        need(Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(reports),
                "missing/symlinked reports directory");
        Map<String, Set<String>> required = loadManifest(manifest);
        int total = 0;
        for (var suite : required.entrySet()) {
            String identity = suite.getKey();
            Path xml = reports.resolve("TEST-" + identity + ".xml");
            need(Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(xml)
                    && Files.size(xml) > 0 && Files.size(xml) <= MAX_XML_BYTES,
                    "missing, symlinked, empty, or oversized report: " + identity);
            Element root = parseXml(xml);
            need(root != null && "testsuite".equals(root.getTagName()), "invalid testsuite root: " + identity);
            need(identity.equals(root.getAttribute("name")), "wrong testsuite identity: " + identity);
            need(attribute(root, "tests") == suite.getValue().size(), "wrong tests count: " + identity);
            for (String status : List.of("errors", "failures", "skipped")) {
                need(attribute(root, status) == 0, "nonzero " + status + " in " + identity);
            }
            NodeList cases = root.getElementsByTagName("testcase");
            need(cases.getLength() == suite.getValue().size(), "missing or extra testcases: " + identity);
            Set<String> executed = new LinkedHashSet<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Node item = cases.item(i);
                need(item instanceof Element, "non-element testcase");
                Element test = (Element) item;
                need(test.getParentNode() == root, "nested testcase: " + identity);
                need(identity.equals(test.getAttribute("classname")), "wrong testcase classname: " + identity);
                String name = test.getAttribute("name");
                need(suite.getValue().contains(name) && executed.add(name),
                        "unknown or duplicate testcase " + identity + "#" + name);
                for (String status : List.of("error", "failure", "skipped")) {
                    need(test.getElementsByTagName(status).getLength() == 0,
                            "failure, error or skipped testcase: " + identity + "#" + name);
                }
            }
            need(executed.equals(suite.getValue()), "missing required method in " + identity);
            total += executed.size();
        }
        need(total == REQUIRED_CASES, "wrong aggregate executed case count");
        return total;
    }

    private static void syntheticReports(Path dir, Map<String, Set<String>> manifest) throws Exception {
        for (var suite : manifest.entrySet()) {
            StringBuilder content = new StringBuilder("<testsuite name=\"").append(suite.getKey())
                    .append("\" tests=\"").append(suite.getValue().size())
                    .append("\" errors=\"0\" failures=\"0\" skipped=\"0\">");
            for (String name : suite.getValue()) {
                content.append("<testcase classname=\"").append(suite.getKey())
                        .append("\" name=\"").append(name).append("\"/>");
            }
            content.append("</testsuite>");
            Files.writeString(dir.resolve("TEST-" + suite.getKey() + ".xml"), content.toString());
        }
    }

    private static void mustReject(String reason, String head, String tree, Path manifest, Path dir) throws Exception {
        try { verify(head, tree, manifest, dir); }
        catch (Exception expected) {
            System.out.println("PASS self-test rejection: " + reason);
            return;
        }
        throw new IllegalStateException("synthetic invalid evidence was accepted: " + reason);
    }

    private static void selfTest(Path manifest) throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        Path dir = Files.createTempDirectory("atto-ownership-receipt-selftest-");
        try {
            Map<String, Set<String>> methods = loadManifest(manifest);
            syntheticReports(dir, methods);
            need(verify(sha, sha, manifest, dir) == REQUIRED_CASES, "baseline synthetic shape failed");
            Path first = dir.resolve("TEST-dev.oreslang.AttoOwnershipWave1GuestTest.xml");
            String valid = Files.readString(first);
            Files.writeString(first, valid.replace("skipped=\"0\"", "skipped=\"1\""));
            mustReject("skipped suite", sha, sha, manifest, dir);
            Files.writeString(first, valid.replace("failures=\"0\"", "failures=\"1\""));
            mustReject("failed suite", sha, sha, manifest, dir);
            Files.writeString(first, valid.replace("<testcase classname=\"", "<testcase classname=\"forged."));
            mustReject("wrong testcase identity", sha, sha, manifest, dir);
            Files.writeString(first, valid.replaceFirst("name=\"copyScalarGuestExitsFortyTwo\"",
                    "name=\"forgedCase\""));
            mustReject("missing expected method", sha, sha, manifest, dir);
            Files.writeString(first, "<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + valid);
            mustReject("XML entity injection", sha, sha, manifest, dir);
            Files.delete(first);
            mustReject("missing suite report", sha, sha, manifest, dir);
            syntheticReports(dir, methods);
            mustReject("bad exact head", "not-a-sha", sha, manifest, dir);
            need(verify(sha, sha, manifest, dir) == REQUIRED_CASES, "synthetic baseline not restored");
            System.out.println("SELF_TEST_OK; synthetic fixtures never attest executed compiler behavior");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path path : walk.sorted((a,b)->b.compareTo(a)).toList()) Files.deleteIfExists(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--self-test".equals(args[0])) {
            selfTest(Path.of(args[1]));
            return;
        }
        need(args.length == 4, "usage: <head-sha> <tree-sha> <manifest> <reports>");
        int count = verify(args[0], args[1], Path.of(args[2]), Path.of(args[3]));
        System.out.println("OWNERSHIP_WAVE1_XML_STRUCTURE_VALID head=" + args[0]
                + " tree=" + args[1] + " methods=" + count);
        System.out.println("NOTE: XML SHAPE IS NOT INDEPENDENT COMPILER EXECUTION ATTESTATION");
    }
}
