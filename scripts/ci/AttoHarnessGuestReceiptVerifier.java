import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Source-owned, JDK-only exact-method Surefire evidence gate.
 * This validates report structure, not the provenance of an untrusted emitter.
 * GitHub Actions must bind the report to an admitted exact source HEAD.
 */
public final class AttoHarnessGuestReceiptVerifier {
    private record Suite(String name, List<String> methods) {
        int count() { return methods.size(); }
    }

    private static final List<Suite> REQUIRED = List.of(
            new Suite("dev.oreslang.AttoNativeTestingGuestIntegrationTest", List.of(
                    "fortyCanonicalAttoCasesCompileAndEmitAllExactOrderedGuestReceipts",
                    "canonicalAttoNegativeGuestsMustCompileThenEmitExpectedSummaryAndFail")),
            new Suite("dev.oreslang.AttoTestingStdlibSourceTest", List.of(
                    "extensionlessTestingImportResolvesCanonicalAttoResource",
                    "legacyOresSuffixIsExplicitAndDoesNotOverrideCanonicalAtto",
                    "rejectsPathTraversalAndForgedCanonicalTestingSuffixes")));

    private static void require(boolean predicate, String message) {
        if (!predicate) throw new IllegalStateException(message);
    }

    private static int number(Element element, String attribute) {
        try { return Integer.parseInt(element.getAttribute(attribute)); }
        catch (NumberFormatException bad) {
            throw new IllegalStateException("missing or invalid " + attribute, bad);
        }
    }

    private static Element parse(Path path) throws Exception {
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
            throw new IllegalStateException("external JUnit XML entity forbidden");
        });
        return builder.parse(path.toFile()).getDocumentElement();
    }

    static int verify(String exactHead, Path reports) throws Exception {
        require(exactHead.matches("[0-9a-f]{40}"), "invalid exact source HEAD");
        require(Files.isDirectory(reports, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(reports), "JUnit report directory missing or symlinked");
        int total = 0;
        for (Suite suite : REQUIRED) {
            Path path = reports.resolve("TEST-" + suite.name() + ".xml");
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path), "missing or symlinked JUnit report: " + suite.name());
            require(Files.size(path) > 0 && Files.size(path) <= 1_048_576,
                    "invalid JUnit report size: " + suite.name());
            Element root = parse(path);
            require(root != null && root.getTagName().equals("testsuite"),
                    "incorrect XML root for " + suite.name());
            require(suite.name().equals(root.getAttribute("name")),
                    "wrong JUnit suite identity: " + suite.name());
            require(number(root, "tests") == suite.count(),
                    "incorrect JUnit test total: " + suite.name());
            for (String field : List.of("errors", "failures", "skipped")) {
                require(number(root, field) == 0,
                        "JUnit " + field + " is nonzero for " + suite.name());
            }
            NodeList cases = root.getElementsByTagName("testcase");
            require(cases.getLength() == suite.count(),
                    "missing/extra JUnit cases for " + suite.name());
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                require(test.getParentNode() == root, "nested JUnit case");
                require(suite.name().equals(test.getAttribute("classname")),
                        "foreign JUnit testcase class");
                String name = test.getAttribute("name");
                require(suite.methods().contains(name) && seen.add(name),
                        "unknown/duplicated JUnit case name: " + name);
                for (String tag : List.of("error", "failure", "skipped")) {
                    require(test.getElementsByTagName(tag).getLength() == 0,
                            "JUnit case failed or was skipped: " + name);
                }
            }
            require(seen.containsAll(suite.methods()), "missing named JUnit method");
            total += suite.count();
        }
        require(total == 5, "wrong aggregate harness JUnit count");
        return total;
    }

    private static void putReport(Path folder, Suite suite, int tests,
                                  int failures, boolean duplicate, boolean foreign,
                                  boolean doctype, boolean nested) throws Exception {
        StringBuilder xml = new StringBuilder();
        if (doctype) xml.append("<!DOCTYPE testsuite [<!ENTITY a SYSTEM 'file:///etc/passwd'>]>");
        xml.append("<testsuite name=\"").append(suite.name()).append("\" tests=\"")
                .append(tests).append("\" errors=\"0\" failures=\"")
                .append(failures).append("\" skipped=\"0\">");
        for (int i = 0; i < tests; i++) {
            if (nested && i == 0) xml.append("<wrapper>");
            xml.append("<testcase classname=\"")
                    .append(foreign && i == 0 ? "forged.Harness" : suite.name())
                    .append("\" name=\"")
                    .append(suite.methods().get(duplicate && i == 1 ? 0 : i))
                    .append("\"/>");
            if (nested && i == 0) xml.append("</wrapper>");
        }
        xml.append("</testsuite>");
        Files.writeString(folder.resolve("TEST-" + suite.name() + ".xml"), xml);
    }

    private static void mustReject(String label, String head, Path folder) throws Exception {
        boolean rejected = false;
        try { verify(head, folder); } catch (Exception expected) { rejected = true; }
        require(rejected, "accepted invalid JUnit evidence: " + label);
    }

    private static void selfTest() throws Exception {
        Path folder = Files.createTempDirectory("atto-harness-xml-");
        String head = "0123456789abcdef0123456789abcdef01234567";
        Suite first = REQUIRED.get(0), second = REQUIRED.get(1);
        try {
            putReport(folder, first, first.count(), 0, false, false, false, false);
            putReport(folder, second, second.count(), 0, false, false, false, false);
            require(verify(head, folder) == 5, "valid JUnit evidence rejected");
            mustReject("wrong HEAD", "not-a-sha", folder);
            putReport(folder, first, first.count(), 1, false, false, false, false);
            mustReject("failure count", head, folder);
            putReport(folder, first, first.count(), 0, true, false, false, false);
            mustReject("duplicate method", head, folder);
            putReport(folder, first, first.count(), 0, false, true, false, false);
            mustReject("foreign class", head, folder);
            putReport(folder, first, first.count(), 0, false, false, true, false);
            mustReject("external DTD", head, folder);
            putReport(folder, first, first.count(), 0, false, false, false, true);
            mustReject("nested method", head, folder);
            putReport(folder, first, first.count(), 0, false, false, false, false);
            Files.delete(folder.resolve("TEST-" + second.name() + ".xml"));
            mustReject("missing suite", head, folder);
            putReport(folder, second, second.count(), 0, false, false, false, false);
            Path report = folder.resolve("TEST-" + first.name() + ".xml");
            String valid = Files.readString(report);
            Files.writeString(report, valid.replace(first.methods().get(0), "wrongName"));
            mustReject("forged method", head, folder);
            Files.writeString(report, valid + " ".repeat(1_048_576));
            mustReject("oversized report", head, folder);
            System.out.println("ATTO_HARNESS_JUNIT_RECEIPT_SELF_TEST_PASS");
        } finally {
            try (var paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException ignored) { }
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            return;
        }
        require(args.length == 2, "usage: EXACT_HEAD SUREFIRE_DIRECTORY");
        int methods = verify(args[0], Path.of(args[1]));
        System.out.println("ATTO_HARNESS_JUNIT_RECEIPT_PASS head=" + args[0]
                + " methods=" + methods);
    }
}
