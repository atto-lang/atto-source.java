import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Standalone Java source-file receipt verifier; intentionally no project dependencies. */
public final class DurableM0ReceiptVerifier {
    private static final Map<String, Integer> EXPECTED = Map.of(
            "dev.oreslang.DurableActivityWorkflowDeclarationTest", 9,
            "dev.oreslang.DurableWorkflowSemanticAnalysisTest", 19,
            "dev.oreslang.AttoSourceExtensionTest", 5,
            "dev.oreslang.launcher.AttoCliSourceExtensionTest", 2);

    private DurableM0ReceiptVerifier() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one Surefire reports directory required");
        Path dir = Path.of(args[0]);
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Surefire reports directory missing or symlinked");
        }
        long count;
        try (var files = Files.list(dir)) {
            count = files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith(".xml")).count();
        }
        if (count != EXPECTED.size()) {
            throw new IllegalArgumentException("expected exactly " + EXPECTED.size()
                    + " Surefire XML reports; found " + count);
        }
        int total = 0;
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        for (var entry : EXPECTED.entrySet()) {
            String className = entry.getKey();
            Path report = dir.resolve("TEST-" + className + ".xml");
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("missing or linked test report: " + report.getFileName());
            }
            Document doc = factory.newDocumentBuilder().parse(report.toFile());
            Element suite = doc.getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())
                    || !className.equals(suite.getAttribute("name"))) {
                throw new IllegalArgumentException("wrong test suite identity: " + report.getFileName());
            }
            int declared = parseCount(suite, "tests");
            if (declared != entry.getValue()) {
                throw new IllegalArgumentException("unexpected test count for " + className + ": " + declared);
            }
            for (String key : new String[]{"failures", "errors", "skipped"}) {
                if (parseCount(suite, key) != 0) {
                    throw new IllegalArgumentException("nonpassing " + key + " in " + className);
                }
            }
            NodeList cases = suite.getElementsByTagName("testcase");
            if (cases.getLength() != declared) {
                throw new IllegalArgumentException("declared versus actual test cases mismatch in " + className);
            }
            Set<String> identities = new HashSet<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                String identity = test.getAttribute("classname") + "#" + test.getAttribute("name");
                if (!className.equals(test.getAttribute("classname"))
                        || test.getAttribute("name").isBlank() || !identities.add(identity)) {
                    throw new IllegalArgumentException("missing, mismatched or duplicated test identity: " + identity);
                }
                for (String tag : new String[]{"failure", "error", "skipped"}) {
                    if (test.getElementsByTagName(tag).getLength() != 0) {
                        throw new IllegalArgumentException("hidden failed or skipped case: " + identity);
                    }
                }
            }
            total += declared;
        }
        System.out.println("ATTO_M0_SUREFIRE_VERIFIED|tests=" + total
                + "|failures=0|errors=0|skipped=0|suites=" + count);
    }

    private static int parseCount(Element suite, String key) {
        String value = suite.getAttribute(key);
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("missing/noncanonical " + key);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("out of range " + key, e); }
    }
}
