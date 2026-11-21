import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Source-file-mode CI verifier: no Python, no third-party XML dependencies. */
final class VerifyDopInvertedJUnit {
    private static final Set<String> REQUIRED = Set.of(
            "productionLexerAndRestrictedMirRunCanonicalSwapReuse",
            "immutableColumnRemainsAttachedToSurvivorAfterSwapDelete",
            "canonicalFormerDenseIndexTrapsAfterSwapErase",
            "checkedMutableFieldStoreAndImmutableColumnRejection",
            "capacityAndLogicalLengthAreDistinctCheckedProperties",
            "strictAdmissionRejectsEffectsAndHiddenUserCode",
            "normalGuestCompilerMustRemainFailClosed");

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments allowed");
        Path report = Path.of(
                "target/surefire-reports/TEST-dev.oreslang.compiler.DopInvertedGuestI64V1Test.xml");
        if (Files.isSymbolicLink(report) || !Files.isRegularFile(report)
                || Files.size(report) == 0 || Files.size(report) > 1_048_576) {
            throw new IllegalStateException("missing or invalid restricted guest JUnit report");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        if (!"testsuite".equals(root.getTagName()) ||
                !"dev.oreslang.compiler.DopInvertedGuestI64V1Test".equals(root.getAttribute("name"))) {
            throw new IllegalStateException("unexpected restricted guest JUnit suite");
        }
        int tests = Integer.parseInt(root.getAttribute("tests"));
        int failures = Integer.parseInt(root.getAttribute("failures"));
        int errors = Integer.parseInt(root.getAttribute("errors"));
        int skipped = Integer.parseInt(root.getAttribute("skipped"));
        if (tests != REQUIRED.size() || failures != 0 || errors != 0 || skipped != 0) {
            throw new IllegalStateException("restricted guest suite not fully executed");
        }
        NodeList cases = root.getElementsByTagName("testcase");
        Set<String> observed = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            String name = testCase.getAttribute("name");
            if (!"dev.oreslang.compiler.DopInvertedGuestI64V1Test".equals(testCase.getAttribute("classname"))
                    || !observed.add(name)) {
                throw new IllegalStateException("unexpected or duplicate guest test case");
            }
            if (testCase.getElementsByTagName("failure").getLength() > 0 ||
                    testCase.getElementsByTagName("error").getLength() > 0 ||
                    testCase.getElementsByTagName("skipped").getLength() > 0) {
                throw new IllegalStateException("guest test case was not successful");
            }
        }
        if (!observed.equals(REQUIRED)) {
            throw new IllegalStateException("restricted guest test inventory mismatch");
        }
        System.out.println("DOP_JAVA_RESTRICTED_GUEST_EXECUTED=" + tests);
    }
}
