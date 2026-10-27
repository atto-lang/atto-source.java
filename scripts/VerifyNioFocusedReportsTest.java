import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/** Executable report-contract regressions; requires only a standard JDK. */
final class VerifyNioFocusedReportsTest {
    private static final String SUITES = "OresCarrierPermitPoolTest,OresCarrierBudgetTest,AdaptiveCarrierExecutorTest,NioReadinessGroupTest,OresVMNioLifecycleTest,VmNioTcpListenerTest,OresSchedulerTest,ActorFutureBlockingTest,OresFutureTerminalNotificationRaceTest,FormalPhysicalStopJobModelCheckTest,FormalContextShutdownOrderModelCheckTest,ActorShardStopRaceTest,AsyncNativeIoTest,ActorHeapPlacementGuardTest,ActorHeapAdmissionTest,ActorPhysicalTurnCreditsTest,NativeNetStdlibTest,StrictNonblockingStdlibIoTest,CanonicalCoreLibraryTest,CoreStdLibraryImportProvenanceTest,CoreStdLibraryProfileAdmissionTest";
    private static final Map<String, List<String>> CASES = Map.of(
            "NioReadinessGroupTest", List.of(
                    "stalledTcpReadDoesNotOccupyTheOnlyCompletionCarrierAndCancelRearms",
                    "outboundConnectUsesSelectorAndTransfersNonblockingChannel",
                    "unresolvedConnectFailsClosedBeforeReactorOrDnsAdmission",
                    "refusedConnectRetiresLeaseAndDoesNotPoisonLaterConnect"),
            "NativeNetStdlibTest", List.of(
                    "realGuestOutboundConnectReadWriteThroughNonblockingKernel"),
            "StrictNonblockingStdlibIoTest", List.of(
                    "hostnameConnectFailsClosedBeforeBlockingDns"));

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0
                ? "scripts/VerifyNioFocusedReports.java" : args[0]).toAbsolutePath();
        Path temp = Files.createTempDirectory("nio-report-contract-");
        try {
            Path classes = Files.createDirectory(temp.resolve("classes"));
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null || compiler.run(null, null, null, "-d",
                    classes.toString(), source.toString()) != 0)
                throw new AssertionError("report verifier did not compile");
            for (String mode : List.of(
                    "complete", "subset", "duplicate-suite", "unknown-suite",
                    "wrong-package", "wrong-testcase-class", "missing-report",
                    "empty-report", "skipped-only", "missing-required",
                    "skipped-required", "duplicate-required", "wrong-count",
                    "failure-with-zero-counter", "nested-testcase", "doctype")) {
                Path reports = Files.createDirectory(temp.resolve(mode));
                writeReports(reports, mode);
                String selection = switch (mode) {
                    case "subset" -> "OresCarrierPermitPoolTest";
                    case "duplicate-suite" -> SUITES + ",OresCarrierPermitPoolTest";
                    case "unknown-suite" -> SUITES + ",UnknownSuite";
                    default -> SUITES;
                };
                Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classes.toString(), "VerifyNioFocusedReports",
                        reports.toString(), selection).redirectErrorStream(true).start();
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError(mode + ": verifier did not terminate");
                }
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                boolean success = process.exitValue() == 0;
                if (success != mode.equals("complete"))
                    throw new AssertionError(mode + ": unexpected result\n" + output);
                System.out.println("PASS " + mode);
            }
            System.out.println("NIO_REPORT_CONTRACT_TESTS_PASS cases=16");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void writeReports(Path directory, String mode) throws Exception {
        for (String suite : SUITES.split(",")) {
            String className = (suite.equals("CoreStdLibraryProfileAdmissionTest")
                    ? "dev.oreslang.config." : "dev.oreslang.runtime.") + suite;
            boolean target = suite.equals("OresCarrierPermitPoolTest");
            boolean required = suite.equals("NioReadinessGroupTest");
            if (target && mode.equals("missing-report")) continue;
            String reportClass = target && mode.equals("wrong-package")
                    ? "unrelated.package." + suite : className;
            String caseClass = target && mode.equals("wrong-testcase-class")
                    ? "unrelated.package." + suite : reportClass;
            var names = new java.util.ArrayList<>(CASES.getOrDefault(
                    suite, List.of("executedRegression")));
            if (required && mode.equals("missing-required")) names.remove(0);
            if (required && mode.equals("duplicate-required")) names.add(names.get(0));
            if (target && mode.equals("empty-report")) names.clear();
            int skipped = target && mode.equals("skipped-only")
                    || required && mode.equals("skipped-required") ? 1 : 0;
            int tests = names.size() + (target && mode.equals("wrong-count") ? 1 : 0);
            StringBuilder xml = new StringBuilder();
            if (target && mode.equals("doctype"))
                xml.append("<!DOCTYPE testsuite [<!ENTITY local 'unexpected'>]>");
            xml.append("<testsuite name=\"").append(reportClass)
                    .append("\" tests=\"").append(tests)
                    .append("\" skipped=\"").append(skipped)
                    .append("\" errors=\"0\" failures=\"0\">");
            if (target && mode.equals("nested-testcase")) xml.append("<nested>");
            for (int i = 0; i < names.size(); i++) {
                xml.append("<testcase classname=\"").append(caseClass)
                        .append("\" name=\"").append(names.get(i)).append("\">");
                if (i == 0 && skipped != 0) xml.append("<skipped/>");
                if (target && mode.equals("failure-with-zero-counter"))
                    xml.append("<failure message=\"failed\"/>");
                xml.append("</testcase>");
            }
            if (target && mode.equals("nested-testcase")) xml.append("</nested>");
            xml.append("</testsuite>");
            Files.writeString(directory.resolve("TEST-" + reportClass + ".xml"), xml);
        }
    }
}
