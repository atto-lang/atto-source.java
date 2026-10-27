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
import org.w3c.dom.NodeList;

/**
 * Source-only focused JUnit receipt checker. It does not attest GitHub
 * provenance, guest AOT compilation, or physical scheduler handoff by itself.
 */
public final class VerifyCooperationReports {
    private VerifyCooperationReports() { }

    private static int count(Element suite, String name) {
        String value = suite.getAttribute(name);
        if (!value.matches("[0-9]+")) {
            throw new IllegalStateException("missing/noninteger " + name);
        }
        return Integer.parseInt(value);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: VerifyCooperationReports REPORT_DIR");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String,Set<String>> required = new LinkedHashMap<>();
        required.put("dev.oreslang.SourceLoopSoftPreemptionTest", Set.of(
                "cpuHeavyGeneratorUsesSameBoundedReductionQuantumAsSourceTasks",
                "automaticGeneratorCooperationDefersUntilMutexGuardIsReleased",
                "implicitGeneratorCooperationRejectsLiveBorrowAtLoopBackedge",
                "ordinaryHelperDoesNotInheritUncheckedGeneratorCooperationPermit",
                "generatorForOfCooperatesOnlyAfterProcessingEachPulledItem"));
        required.put("dev.oreslang.runtime.AsyncRuntimeCooperationTest", Set.of(
                "ordinaryPlatformThreadCannotClaimItCooperatedAsAGenerator",
                "runtimeOwnedVirtualCarrierCanCooperateAndResume",
                "cancellationIsObservedBeforeGeneratorCarrierCanYield"));
        required.put("dev.oreslang.runtime.SourceCooperationQuantumTest", Set.of());
        required.put("dev.oreslang.runtime.OresSchedulerTest", Set.of());

        int total = 0;
        for (Map.Entry<String,Set<String>> entry : required.entrySet()) {
            String klass = entry.getKey();
            Path report = root.resolve("TEST-" + klass + ".xml").normalize();
            if (!report.getParent().equals(root)
                    || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(report)
                    || Files.size(report) > 2 * 1024 * 1024) {
                throw new IllegalStateException("missing/unsafe cooperation XML: " + klass);
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document xml;
            try (var stream = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                xml = factory.newDocumentBuilder().parse(stream);
            }
            Element suite = xml.getDocumentElement();
            if (!"testsuite".equals(suite.getTagName())
                    || !klass.equals(suite.getAttribute("name"))) {
                throw new IllegalStateException("wrong suite identity: " + klass);
            }
            int cases = count(suite, "tests");
            if (cases == 0 || count(suite, "errors") != 0
                    || count(suite, "failures") != 0
                    || count(suite, "skipped") != 0) {
                throw new IllegalStateException("failed/missing/skipped-only suite: " + klass);
            }
            NodeList testcases = suite.getElementsByTagName("testcase");
            if (testcases.getLength() != cases) {
                throw new IllegalStateException("testcase count mismatch: " + klass);
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                String name = testcase.getAttribute("name");
                if (!klass.equals(testcase.getAttribute("classname"))
                        || name.isBlank() || !seen.add(name)
                        || testcase.getElementsByTagName("failure").getLength() != 0
                        || testcase.getElementsByTagName("error").getLength() != 0
                        || testcase.getElementsByTagName("skipped").getLength() != 0) {
                    throw new IllegalStateException("invalid/duplicate testcase: " + klass + "." + name);
                }
            }
            if (!seen.containsAll(entry.getValue())) {
                Set<String> missing = new HashSet<>(entry.getValue());
                missing.removeAll(seen);
                throw new IllegalStateException("missing required cooperation cases: " + missing);
            }
            total += cases;
            System.out.println("COOPERATION_SUITE_EXECUTED " + klass + " cases=" + cases);
        }
        System.out.println("ORESLANG_UNIFIED_COOPERATION_FOCUSED_PASS cases=" + total
                + " source_guest_aot=NOT_PROVEN");
    }
}
