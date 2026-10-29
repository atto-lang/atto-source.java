import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Fail closed on partial, skipped, duplicate or malformed native guest evidence. */
public final class VerifyNativeGuestReports {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("report directory required");
        verify(Path.of(args[0]), "dev.oreslang.SourceActorStepWorkflowExecutionTest", 4);
        verify(Path.of(args[0]), "dev.oreslang.SourceActorDemoWorkflowExecutionTest", 14);
        System.out.println("NATIVE_GUEST_REPORTS_VERIFIED cases=18");
    }
    static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }
    static void verify(Path directory, String suite, int expected) throws Exception {
        Path path = directory.resolve("TEST-" + suite + ".xml");
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path), "missing regular report: " + suite);
        require(Files.size(path) <= 8 * 1024 * 1024, "oversized report");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        require(root.getTagName().equals("testsuite") && root.getAttribute("name").equals(suite), "wrong suite");
        require(root.getAttribute("tests").equals(Integer.toString(expected)), "wrong test total");
        for (String counter : List.of("failures", "errors", "skipped")) {
            require(root.getAttribute(counter).equals("0"), "nonzero/missing " + counter);
        }
        var cases = root.getElementsByTagName("testcase");
        require(cases.getLength() == expected, "testcase count mismatch");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element c = (Element) cases.item(i);
            require(c.getParentNode() == root, "nested testcase");
            require(c.getAttribute("classname").equals(suite), "wrong testcase class");
            require(!c.getAttribute("name").isBlank() && names.add(c.getAttribute("name")), "empty/duplicate testcase");
        }
        for (String forbidden : List.of("failure", "error", "skipped", "testsuite")) {
            require(root.getElementsByTagName(forbidden).getLength() == 0, "unexpected " + forbidden);
        }
    }
}
