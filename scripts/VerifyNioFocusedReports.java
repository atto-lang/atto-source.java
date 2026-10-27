import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Reject empty, incomplete or skipped-only focused JUnit qualification. */
final class VerifyNioFocusedReports {
    private static final java.util.Map<String, java.util.Set<String>> REQUIRED_CASES =
            java.util.Map.of(
                    "NioReadinessGroupTest", java.util.Set.of(
                            "stalledTcpReadDoesNotOccupyTheOnlyCompletionCarrierAndCancelRearms",
                            "outboundConnectUsesSelectorAndTransfersNonblockingChannel",
                            "unresolvedConnectFailsClosedBeforeReactorOrDnsAdmission",
                            "refusedConnectRetiresLeaseAndDoesNotPoisonLaterConnect"),
                    "NativeNetStdlibTest", java.util.Set.of(
                            "realGuestOutboundConnectReadWriteThroughNonblockingKernel"),
                    "StrictNonblockingStdlibIoTest", java.util.Set.of(
                            "hostnameConnectFailsClosedBeforeBlockingDns"));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: reports-directory suite1,suite2,...");
        }
        Path directory = Path.of(args[0]);
        List<Path> reports;
        try (var files = Files.list(directory)) {
            reports = files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith(".xml")).toList();
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        int totalExecuted = 0;
        var problems = new ArrayList<String>();
        var selected = new java.util.HashSet<String>();
        for (String suite : args[1].split(",", -1)) {
            if (!suite.matches("[A-Za-z][A-Za-z0-9]*") || !selected.add(suite)) {
                throw new IllegalArgumentException("invalid or duplicate suite: " + suite);
            }
            var matches = reports.stream().filter(path ->
                    path.getFileName().toString().endsWith("." + suite + ".xml")).toList();
            if (matches.size() != 1) {
                problems.add(suite + ": expected one report, found " + matches.size());
                continue;
            }
            Element root = factory.newDocumentBuilder().parse(matches.get(0).toFile())
                    .getDocumentElement();
            int tests = count(root, "tests");
            int skipped = count(root, "skipped");
            int failures = count(root, "failures");
            int errors = count(root, "errors");
            var cases = root.getElementsByTagName("testcase");
            int observedSkipped = root.getElementsByTagName("skipped").getLength();
            boolean identityMatches = root.getTagName().equals("testsuite")
                    && root.getAttribute("name").endsWith("." + suite)
                    && matches.get(0).getFileName().toString()
                            .equals("TEST-" + root.getAttribute("name") + ".xml");
            boolean valid = identityMatches && tests == cases.getLength()
                    && skipped == observedSkipped && tests > skipped
                    && failures == 0 && errors == 0
                    && root.getElementsByTagName("failure").getLength() == 0
                    && root.getElementsByTagName("error").getLength() == 0;
            var requiredCases = REQUIRED_CASES.getOrDefault(suite, java.util.Set.of());
            var observedRequired = new java.util.HashSet<String>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                String name = test.getAttribute("name");
                if (requiredCases.contains(name)
                        && test.getElementsByTagName("skipped").getLength() == 0) {
                    observedRequired.add(name);
                }
            }
            if (!observedRequired.equals(requiredCases)) {
                var missing = new java.util.HashSet<>(requiredCases);
                missing.removeAll(observedRequired);
                problems.add(suite + ": missing required executed case(s) " + missing);
                valid = false;
            }
            System.out.printf("%s tests=%d executed=%d skipped=%d failures=%d errors=%d%n",
                    suite, tests, tests - skipped, skipped, failures, errors);
            if (!valid) problems.add(suite + ": incomplete or unsuccessful execution");
            totalExecuted += tests - skipped;
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
        System.out.printf("NIO_FOCUSED_SUITES_PASS suites=%d executed=%d%n",
                selected.size(), totalExecuted);
    }

    private static int count(Element root, String attribute) {
        int value = Integer.parseInt(root.getAttribute(attribute));
        if (value < 0) throw new IllegalArgumentException("negative " + attribute);
        return value;
    }
}
