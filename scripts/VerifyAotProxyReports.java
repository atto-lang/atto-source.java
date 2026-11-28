import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Fail-closed, dependency-free Surefire receipt verifier for exact-head AOT proxy JUnit. */
class VerifyAotProxyReports {
    private static int integerAttr(Element root, String name) {
        String value = root.getAttribute(name);
        if (!value.matches("[0-9]+")) throw new IllegalStateException("missing/invalid " + name);
        return Integer.parseInt(value);
    }

    public static void main(String[] args) throws Exception {
        final String expectedSuite = "dev.oreslang.AotClassProxyLanguageTest";
        Path path = Path.of("target", "surefire-reports", "TEST-" + expectedSuite + ".xml");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) {
            throw new IllegalStateException("proxy Surefire receipt missing");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        if (!"testsuite".equals(root.getTagName()) || !expectedSuite.equals(root.getAttribute("name"))) {
            throw new IllegalStateException("unexpected proxy Surefire suite identity");
        }
        int count = integerAttr(root, "tests");
        if (count < 49 || integerAttr(root, "failures") != 0 ||
            integerAttr(root, "errors") != 0 || integerAttr(root, "skipped") != 0) {
            throw new IllegalStateException("proxy tests missing, failed or skipped");
        }
        NodeList cases = root.getElementsByTagName("testcase");
        if (cases.getLength() != count) {
            throw new IllegalStateException("proxy testcase count disagrees with declared suite count");
        }
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element tc = (Element) cases.item(i);
            String method = tc.getAttribute("name");
            if (!expectedSuite.equals(tc.getAttribute("classname")) ||
                method.isBlank() || !unique.add(method) ||
                tc.getElementsByTagName("failure").getLength() != 0 ||
                tc.getElementsByTagName("error").getLength() != 0 ||
                tc.getElementsByTagName("skipped").getLength() != 0) {
                throw new IllegalStateException("bad proxy testcase identity or result");
            }
        }
        System.out.println("ATTO_PROXY_TEST_COUNT=" + count);
        System.out.println("ATTO_PROXY_GUEST_RECEIPT=present");
    }
}
