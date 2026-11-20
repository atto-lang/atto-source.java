import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Fail-closed verification of real Maven Surefire JUnit XML evidence.
 * This is a JDK-only CI gate, not execution of any AttoLang guest.
 */
public final class CheckCanonicalXorReports {
    private static final String PACKAGE = "dev.oreslang.";
    private static final String XOR_SUITE = "AttoCanonicalIntegerXorParityTest";
    private static final String BOOL_SUITE = "BooleanIntrinsicsTest";

    private CheckCanonicalXorReports() { }

    private static Set<String> methods(String... names) {
        return Set.copyOf(Arrays.asList(names));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments permitted");
        check(XOR_SUITE, methods(
                "strictIntegerXorCompilesAndRunsInActualTruffleGuest",
                "singleCaretReturnAndContiguousIntegerXorWorkTogether",
                "canonicalBooleanXorAndMixedScalarKindsFailDuringAdmission",
                "rejectedSingleOrSpacedCaretNeverCreatesCheckedAst",
                "canonicalIntegerXorRhsFailureExecutesEffectsOnlyOnce",
                "legacyParserAndTypecheckerRetainEagerBooleanXor",
                "booleanXorLegacyGuestStillWorks",
                "strictSignedI64XorPrecedenceAndLeftAssociativeChain",
                "strictXorSuccessEvaluatesEachOperandExactlyOnceFromLeftToRight",
                "strictBooleanShortCircuitStillSkipsInvalidRightOperands"));
        check(BOOL_SUITE, methods(
                "builtinGlobalsNeedNoImportsAndSurviveBuildAdmission",
                "exhaustiveTruthTablesForGlobalAndQualifiedScalarAndArrayForms",
                "typedArrayVariablesWorkForEveryIntrinsic",
                "scalarShortCircuitingSkipsOnlyOperandsAfterTheDecisiveValue",
                "everyIntrinsicRejectsEmptyCallsScalarSingletonsAndNonBooleanOperands",
                "shadowedFunctionsKeepTheirDeclaredArrayOwnership",
                "intrinsicArraysCanBeReadRepeatedlyInsideLoops",
                "typeChecksAritySelectedScalarAndListOverloads",
                "rejectsInvalidArityAndOperandTypes",
                "moduleAndTopLevelFunctionsOverloadByNameAndExactArity",
                "realBooleanOpsModuleShadowsBuiltinNamespace",
                "runtimeUsesLogicalIdentitiesParityAndScalarShortCircuiting"));
        System.out.println("RECEIPT required_java_xor_junit=PASS native_image=NOT_EXECUTED");
    }

    private static int count(Element root, String name) {
        String value = root.getAttribute(name);
        if (!value.matches("[0-9]{1,5}"))
            throw new IllegalStateException("Invalid or missing JUnit attribute: " + name);
        return Integer.parseInt(value);
    }

    private static void check(String suite, Set<String> expected) throws Exception {
        Path report = Path.of("target", "surefire-reports", "TEST-" + PACKAGE + suite + ".xml");
        if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Missing or symlinked JUnit report: " + report);

        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(report.toFile());
        Element root = doc.getDocumentElement();
        if (root == null || !"testsuite".equals(root.getTagName())
                || !Set.of(suite, PACKAGE + suite).contains(root.getAttribute("name")))
            throw new IllegalStateException("Unexpected JUnit suite identity: " + suite);
        int tests = count(root, "tests");
        if (count(root, "errors") != 0 || count(root, "failures") != 0
                || count(root, "skipped") != 0)
            throw new IllegalStateException("JUnit failures, errors, or skips: " + suite);
        NodeList cases = root.getElementsByTagName("testcase");
        if (tests != cases.getLength() || tests < expected.size())
            throw new IllegalStateException("JUnit test count does not match executed cases: " + suite);
        if (root.getElementsByTagName("error").getLength() != 0
                || root.getElementsByTagName("failure").getLength() != 0
                || root.getElementsByTagName("skipped").getLength() != 0)
            throw new IllegalStateException("JUnit contains a failed or skipped case: " + suite);
        Set<String> executed = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
            if (!PACKAGE.concat(suite).equals(test.getAttribute("classname")))
                throw new IllegalStateException("Wrong test classname: " + suite);
            String name = test.getAttribute("name");
            if (name.isBlank() || !executed.add(name))
                throw new IllegalStateException("Missing or duplicated test case: " + suite);
        }
        if (!executed.containsAll(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(executed);
            throw new IllegalStateException("Missing required JUnit methods: " + suite + " " + missing);
        }
        System.out.printf("RECEIPT suite=%s executed=%d fail=0 error=0 skip=0%n", suite, tests);
    }
}
