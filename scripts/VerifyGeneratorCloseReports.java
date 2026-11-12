import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Fail-closed Surefire evidence checker. Never treats zero/skipped cases as execution. */
public final class VerifyGeneratorCloseReports {
    private VerifyGeneratorCloseReports() { }

    private static Element parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException failure) throws SAXParseException {
                throw failure;
            }
        });
        return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }

    private static int number(Element element, String key) {
        if (!element.hasAttribute(key)) {
            throw new IllegalArgumentException("missing attribute: " + key);
        }
        String value = element.getAttribute(key);
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException("invalid numeric attribute: " + key);
        }
        return Integer.parseInt(value);
    }

    private static int verify(byte[] xml, int expectedCount) throws Exception {
        Element suite = parse(xml);
        if (!"testsuite".equals(suite.getTagName())) {
            throw new IllegalArgumentException("not a Surefire testsuite");
        }
        int tests = number(suite, "tests");
        int failures = number(suite, "failures");
        int errors = number(suite, "errors");
        int skipped = number(suite, "skipped");
        int actualCases = suite.getElementsByTagName("testcase").getLength();
        if (tests == 0 || (expectedCount > 0 && tests != expectedCount)
                || actualCases != tests || failures != 0 || errors != 0 || skipped != 0) {
            throw new IllegalStateException("invalid executed suite: tests=" + tests
                    + " cases=" + actualCases + " failed=" + failures
                    + " errors=" + errors + " skipped=" + skipped);
        }
        return tests;
    }

    private static byte[] sample(int tests, int cases, int failures, int errors, int skipped) {
        String xml = "<testsuite tests=\"" + tests + "\" failures=\"" + failures
                + "\" errors=\"" + errors + "\" skipped=\"" + skipped + "\">"
                + "<testcase name=\"ok\"/>".repeat(cases) + "</testsuite>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static void selfTest() throws Exception {
        if (verify(sample(3, 3, 0, 0, 0), 3) != 3) throw new AssertionError();
        int rejects = 0;
        byte[][] forged = {
            sample(0, 0, 0, 0, 0), sample(3, 2, 0, 0, 0),
            sample(3, 3, 1, 0, 0), sample(3, 3, 0, 1, 0),
            sample(3, 3, 0, 0, 1), sample(2, 2, 0, 0, 0),
            "<not-a-testsuite/>".getBytes(StandardCharsets.UTF_8),
            "<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><testsuite/>"
                    .getBytes(StandardCharsets.UTF_8)
        };
        for (byte[] bytes : forged) {
            try { verify(bytes, 3); }
            catch (Exception expected) { rejects++; }
        }
        if (rejects != forged.length) throw new AssertionError("forgery accepted");
        System.out.println("PASS generator report verifier: 1 accept, " + rejects + " negative controls");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--selftest".equals(args[0])) {
            selfTest();
            return;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("expected <new regression Surefire XML> <existing runtime Surefire XML>");
        }
        for (int i = 0; i < args.length; i++) {
            int n = verify(Files.readAllBytes(Path.of(args[i])), i == 0 ? 3 : -1);
            System.out.println("GENERATOR_EXECUTED report=" + args[i]
                    + " tests=" + n + " failures=0 errors=0 skipped=0");
        }
    }
}
