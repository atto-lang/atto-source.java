import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Source-owned, fail-closed verification of official Maven Surefire/JUnit XML.
 * Uses only JDK XML APIs; no external dependencies or network access.
 * Report checks do not prove runner authenticity or replace full-build evidence.
 */
public final class VerifyTypedCallableSurefire {
    private static final Map<String, Integer> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("ExplicitLambdaReturnContractTest", 16);
        REQUIRED.put("NamedCallableBindingSignatureTest", 10);
        REQUIRED.put("CallableReturnParserBoundaryTest", 8);
        REQUIRED.put("TypedLambdaTreeShakerContractTest", 4);
    }

    private VerifyTypedCallableSurefire() { }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--self-test".equals(args[0])) {
                selfTest();
                System.out.println("SUREFIRE_REPORT_GUARD_SELF_TEST=PASS");
                return;
            }
            if (args.length != 1) {
                throw new IllegalArgumentException("expected Surefire report directory or --self-test");
            }
            int total = verify(Path.of(args[0]));
            System.out.println("OFFICIAL_JUNIT_COUNTS tests=" + total
                    + " failures=0 errors=0 skipped=0");
        } catch (Exception error) {
            System.err.println("NOT_CERTIFIED: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void require(boolean condition, String error) {
        if (!condition) throw new IllegalArgumentException(error);
    }

    private static int numeric(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        require(value.matches("[0-9]{1,9}"), "missing/invalid " + attribute + " attribute");
        return Integer.parseInt(value);
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException e) throws SAXException { throw e; }
            @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
        });
        return builder.parse(path.toFile());
    }

    private static int verify(Path reportDirectory) throws Exception {
        require(Files.isDirectory(reportDirectory, LinkOption.NOFOLLOW_LINKS),
                "Surefire report directory absent or is a symlink");
        int overall = 0;
        for (var required : REQUIRED.entrySet()) {
            String suiteName = "dev.oreslang." + required.getKey();
            Path report = reportDirectory.resolve("TEST-" + suiteName + ".xml");
            require(Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS),
                    "NOT_EXECUTED: absent, nonregular or symlink report for " + suiteName);
            Document doc = parse(report);
            Element root = doc.getDocumentElement();
            require(root != null && "testsuite".equals(root.getTagName()),
                    "invalid Surefire document root for " + suiteName);
            require(suiteName.equals(root.getAttribute("name")),
                    "wrong test suite identity: " + suiteName);
            int declared = numeric(root, "tests");
            require(declared >= required.getValue(), "too few tests reported for " + suiteName);
            require(numeric(root, "failures") == 0
                    && numeric(root, "errors") == 0
                    && numeric(root, "skipped") == 0,
                    "failed, erroneous or skipped tests in " + suiteName);
            int observed = 0;
            Set<String> names = new HashSet<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element test) || !"testcase".equals(test.getTagName())) {
                    continue;
                }
                observed++;
                require(suiteName.equals(test.getAttribute("classname")),
                        "unexpected testcase class in " + suiteName);
                String testName = test.getAttribute("name");
                require(!testName.isBlank() && names.add(testName),
                        "missing or repeated testcase name in " + suiteName);
                NodeList results = test.getChildNodes();
                for (int n = 0; n < results.getLength(); n++) {
                    Node outcome = results.item(n);
                    if (outcome instanceof Element element) {
                        require(!Set.of("failure", "error", "skipped").contains(element.getTagName()),
                                "case-level failure, error or skip in " + suiteName);
                    }
                }
            }
            require(observed == declared, "testcase count disagrees with suite total in " + suiteName);
            overall += observed;
        }
        require(overall >= 38, "fewer than 38 official testcases");
        return overall;
    }

    private static String xml(String className, int observed, int declared, String extraInFirst) {
        String fullClassName = "dev.oreslang." + className;
        StringBuilder result = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<testsuite name=\"").append(fullClassName)
                .append("\" tests=\"").append(declared)
                .append("\" failures=\"0\" errors=\"0\" skipped=\"0\">\n");
        for (int i = 0; i < observed; i++) {
            result.append("<testcase classname=\"").append(fullClassName)
                    .append("\" name=\"test_").append(i).append("\">");
            if (i == 0) result.append(extraInFirst);
            result.append("</testcase>\n");
        }
        return result.append("</testsuite>\n").toString();
    }

    private static Path report(Path dir, String name) {
        return dir.resolve("TEST-dev.oreslang." + name + ".xml");
    }

    private static void expectDenied(Path folder, String scenario) throws Exception {
        try {
            verify(folder);
        } catch (Exception expected) {
            return;
        }
        throw new IllegalStateException("self-test falsely accepted: " + scenario);
    }

    private static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("surefire-evidence-selftest-");
        String first = REQUIRED.keySet().iterator().next();
        try {
            for (var entry : REQUIRED.entrySet()) {
                Files.writeString(report(dir, entry.getKey()),
                        xml(entry.getKey(), entry.getValue(), entry.getValue(), ""));
            }
            require(verify(dir) == 38, "valid fixture rejected");
            String valid = Files.readString(report(dir, first));
            Files.delete(report(dir, first));
            expectDenied(dir, "missing suite report");
            Files.writeString(report(dir, first), valid.replace("tests=\"16\"", "tests=\"17\""));
            expectDenied(dir, "inflated suite count");
            Files.writeString(report(dir, first), valid.replace("skipped=\"0\"", "skipped=\"1\""));
            expectDenied(dir, "skipped test count");
            Files.writeString(report(dir, first), valid.replace("failures=\"0\"", "failures=\"1\""));
            expectDenied(dir, "failed test count");
            Files.writeString(report(dir, first), valid.replace("dev.oreslang." + first,
                    "dev.oreslang.MismatchedSuite"));
            expectDenied(dir, "wrong suite identity");
            Files.writeString(report(dir, first), valid.replace("name=\"test_1\"", "name=\"test_0\""));
            expectDenied(dir, "duplicate testcase identity");
            Files.writeString(report(dir, first), xml(first, 16, 16, "<skipped/>"));
            expectDenied(dir, "hidden case-level skip");
            Files.writeString(report(dir, first), xml(first, 16, 16, "<failure/>"));
            expectDenied(dir, "hidden case-level failure");
            Files.writeString(report(dir, first), xml(first, 16, 16, "<error/>"));
            expectDenied(dir, "hidden case-level error");
            Files.writeString(report(dir, first), valid.replaceFirst("<testsuite ",
                    "<!DOCTYPE testsuite [<!ENTITY leak SYSTEM 'file:///etc/passwd'>]><testsuite "));
            expectDenied(dir, "DOCTYPE entity injection");
            Files.writeString(report(dir, first), "<testsuite>");
            expectDenied(dir, "malformed XML");
            Files.delete(report(dir, first));
            Files.createSymbolicLink(report(dir, first), report(dir, REQUIRED.keySet().stream()
                    .filter(other -> !first.equals(other)).findFirst().orElseThrow()));
            expectDenied(dir, "symlinked test report");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
