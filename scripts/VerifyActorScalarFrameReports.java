import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Source-free, fail-closed validation of exact JUnit/Surefire test receipts. */
class VerifyActorScalarFrameReports {
    private static int count(Element element, String name) {
        String raw = element.getAttribute(name);
        if (!raw.matches("[0-9]+")) {
            throw new IllegalStateException("Missing or invalid " + name + " in Surefire receipt");
        }
        return Integer.parseInt(raw);
    }

    public static void main(String[] args) throws Exception {
        var required = new LinkedHashMap<String, Integer>();
        required.put("dev.oreslang.runtime.ScalarFrameLayoutTest", 6);
        required.put("dev.oreslang.runtime.ActorScalarFrameStoreTest", 6);
        required.put("dev.oreslang.runtime.ActorScalarFrameCodecTest", 3);

        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        int total = 0;
        for (var entry : required.entrySet()) {
            String name = entry.getKey();
            int expected = entry.getValue();
            Path file = Path.of("target", "surefire-reports", "TEST-" + name + ".xml");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0) {
                throw new IllegalStateException("Missing nonempty Surefire receipt: " + file);
            }
            Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            if (!"testsuite".equals(root.getTagName()) || !name.equals(root.getAttribute("name"))) {
                throw new IllegalStateException("Wrong test suite identity in " + file);
            }
            if (count(root, "tests") != expected || count(root, "failures") != 0 ||
                count(root, "errors") != 0 || count(root, "skipped") != 0) {
                throw new IllegalStateException("Unexpected Surefire counts in " + file);
            }
            NodeList cases = root.getElementsByTagName("testcase");
            if (cases.getLength() != expected) {
                throw new IllegalStateException("Missing executed test cases in " + file);
            }
            Set<String> identities = new HashSet<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String method = tc.getAttribute("name");
                if (!name.equals(tc.getAttribute("classname")) || method.isBlank() ||
                    !identities.add(method) || tc.getElementsByTagName("skipped").getLength() != 0 ||
                    tc.getElementsByTagName("failure").getLength() != 0 ||
                    tc.getElementsByTagName("error").getLength() != 0) {
                    throw new IllegalStateException("Invalid/duplicate/failing test case in " + file);
                }
            }
            System.out.println("FRAME_EXECUTED_SUITE " + name + " tests=" + expected);
            total += expected;
        }
        System.out.println("FRAME_HOST_RUNTIME_TESTS_PASS count=" + total);
    }
}
