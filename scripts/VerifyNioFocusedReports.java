import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Reject empty, incomplete or skipped-only focused JUnit qualification. */
final class VerifyNioFocusedReports {
    private static final java.util.Set<String> REQUIRED_SUITES = java.util.Set.of(
            "OresCarrierPermitPoolTest",
            "OresCarrierBudgetTest",
            "AdaptiveCarrierExecutorTest",
            "NioReadinessGroupTest",
            "OresVMNioLifecycleTest",
            "VmNioTcpListenerTest",
            "VmNioTcpConnectionTest",
            "OresSchedulerTest",
            "ActorFutureBlockingTest",
            "OresFutureTerminalNotificationRaceTest",
            "FormalPhysicalStopJobModelCheckTest",
            "FormalContextShutdownOrderModelCheckTest",
            "ActorShardStopRaceTest",
            "AsyncNativeIoTest",
            "ActorHeapPlacementGuardTest",
            "ActorHeapAdmissionTest",
            "ActorPhysicalTurnCreditsTest",
            "NativeNetStdlibTest",
            "StrictNonblockingStdlibIoTest",
            "CanonicalCoreLibraryTest",
            "CoreStdLibraryImportProvenanceTest",
            "CoreStdLibraryProfileAdmissionTest");

    private static final java.util.Map<String, java.util.Set<String>> REQUIRED_CASES =
            java.util.Map.of(
                    "NioReadinessGroupTest", java.util.Set.of(
                            "stalledTcpReadDoesNotOccupyTheOnlyCompletionCarrierAndCancelRearms",
                            "outboundConnectUsesSelectorAndTransfersNonblockingChannel",
                            "concurrentReadAndWriteShareOneRegistrationAndRetireIndependently",
                            "unresolvedConnectFailsClosedBeforeReactorOrDnsAdmission",
                            "refusedConnectRetiresLeaseAndDoesNotPoisonLaterConnect"),
                    "VmNioTcpConnectionTest", java.util.Set.of(
                            "closeAsyncWaitsForPendingReadRetirementAndRunsTerminalHookOnce",
                            "connectionAllowsOneReadAndOneWriteButRejectsDuplicateRead",
                            "closedOutboundConnectionFailsFurtherIoWithoutReadinessAdmission"),
                    "NativeNetStdlibTest", java.util.Set.of(
                            "realGuestOutboundConnectReadWriteThroughNonblockingKernel",
                            "realGuestDuplexReadAndWriteOverlapOnOneConnection"),
                    "StrictNonblockingStdlibIoTest", java.util.Set.of(
                            "hostnameConnectFailsClosedBeforeBlockingDns"));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: reports-directory suite1,suite2,...");
        }
        var requested = new java.util.HashSet<String>();
        for (String suite : args[1].split(",", -1)) {
            if (!REQUIRED_SUITES.contains(suite) || !requested.add(suite))
                throw new IllegalArgumentException("unknown or duplicate required suite: " + suite);
        }
        if (!requested.equals(REQUIRED_SUITES)) {
            var missing = new java.util.TreeSet<>(REQUIRED_SUITES);
            missing.removeAll(requested);
            throw new IllegalArgumentException("incomplete NIO qualification; missing suites: " + missing);
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
            String expectedClass = (suite.equals("CoreStdLibraryProfileAdmissionTest")
                    ? "dev.oreslang.config." : "dev.oreslang.runtime.") + suite;
            boolean identityMatches = root.getTagName().equals("testsuite")
                    && root.getAttribute("name").equals(expectedClass)
                    && matches.get(0).getFileName().toString()
                            .equals("TEST-" + root.getAttribute("name") + ".xml");
            boolean valid = identityMatches && tests == cases.getLength()
                    && skipped == observedSkipped && tests > skipped
                    && failures == 0 && errors == 0
                    && root.getElementsByTagName("failure").getLength() == 0
                    && root.getElementsByTagName("error").getLength() == 0;
            var requiredCases = REQUIRED_CASES.getOrDefault(suite, java.util.Set.of());
            var observedRequired = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                String name = test.getAttribute("name");
                if (test.getParentNode() != root
                        || !test.getAttribute("classname").equals(expectedClass))
                    valid = false;
                if (requiredCases.contains(name)
                        && test.getElementsByTagName("skipped").getLength() == 0) {
                    observedRequired.merge(name, 1, Integer::sum);
                }
            }
            if (!observedRequired.keySet().equals(requiredCases)
                    || observedRequired.values().stream().anyMatch(count -> count != 1)) {
                var missing = new java.util.HashSet<>(requiredCases);
                missing.removeAll(observedRequired.keySet());
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
