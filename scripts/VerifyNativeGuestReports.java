import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

/** Fail closed on partial, skipped, duplicate, malformed or spoofed native guest evidence. */
public final class VerifyNativeGuestReports {
    private static final long MAX_REPORT_BYTES = 8L * 1024 * 1024;

    private static final Set<String> STEP_CASES = Set.of(
            "executesThreeIndependentStepActorsInSequence",
            "executesTwoStepActorsAndJoinsTheirOutputs",
            "rejectsInvalidStepInputBeforeStartingDownstreamActors",
            "executesCoordinatorActorWithGuardedForkJoinAndDuplicateReceipt");

    private static final Set<String> DEMO_CASES = Set.of(
            "executesAcceptedOrderForkAndJoin",
            "rejectsInvalidOrderBeforeDownstreamWork",
            "executesMemoryFixtureCaptureVerificationAndIndex",
            "quarantinesUntrustedEvidenceWithoutIndex",
            "executesReviewFixtureToPreviewOnly",
            "staysWaitingWithoutApprovalFixture",
            "boundsRetryToTwoDistinctActors",
            "stopsBeforeWorkOnCancellation",
            "rejectsForeignDuplicateAndOutOfOrderMessages",
            "executesSagaSuccessWithNoCompensation",
            "compensatesKnownNoEffectRejectionInReverseOrder",
            "quarantinesAmbiguousSagaOutcomeWithoutCompensation",
            "doesNotReleaseInventoryAfterUnconfirmedPaymentVoid",
            "rejectsDuplicateAndForeignEffectReceiptsAndBlocksUnknownCancellation");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("report directory required");
        Path directory = Path.of(args[0]);
        // An extra Surefire suite is not evidence for either pinned guest suite.
        // Do not silently ignore an unexpected report in the selected test run.
        Set<String> expectedFiles = Set.of(
                "TEST-dev.oreslang.SourceActorStepWorkflowExecutionTest.xml",
                "TEST-dev.oreslang.SourceActorDemoWorkflowExecutionTest.xml");
        BasicFileAttributes directoryAttrs = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(directoryAttrs.isDirectory() && !Files.isSymbolicLink(directory),
                "unsafe report directory");
        Set<String> seen = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "TEST-*.xml")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                require(expectedFiles.contains(name) && seen.add(name),
                        "unexpected/duplicate Surefire report: " + name);
            }
        }
        require(seen.equals(expectedFiles), "missing expected Surefire report");
        verify(directory, "dev.oreslang.SourceActorStepWorkflowExecutionTest", STEP_CASES);
        verify(directory, "dev.oreslang.SourceActorDemoWorkflowExecutionTest", DEMO_CASES);
        System.out.println("NATIVE_GUEST_REPORTS_VERIFIED cases=18");
    }

    static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    static void requireFiniteSeconds(String seconds, String label) {
        require(seconds.matches("[0-9]{1,12}(?:\\.[0-9]{1,9})?(?:[eE][+-]?[0-9]{1,3})?"),
                "invalid/missing " + label);
        require(Double.isFinite(Double.parseDouble(seconds)), "non-finite " + label);
    }

    static void verify(Path directory, String suite, Set<String> expectedNames) throws Exception {
        BasicFileAttributes directoryAttrs = Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(directoryAttrs.isDirectory() && !Files.isSymbolicLink(directory),
                "unsafe report directory");

        Path path = directory.resolve("TEST-" + suite + ".xml");
        BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(attrs.isRegularFile() && !Files.isSymbolicLink(path),
                "missing regular report: " + suite);
        require(attrs.size() <= MAX_REPORT_BYTES, "oversized report");

        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("external entity resolution denied");
        });

        Element root;
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            root = builder.parse(input).getDocumentElement();
        }

        require(root.getTagName().equals("testsuite") && root.getAttribute("name").equals(suite),
                "wrong suite");
        require(root.getAttribute("tests").equals(Integer.toString(expectedNames.size())),
                "wrong test total");
        for (String counter : List.of("failures", "errors", "skipped")) {
            require(root.getAttribute(counter).equals("0"), "nonzero/missing " + counter);
        }
        requireFiniteSeconds(root.getAttribute("time"), "suite time");

        var cases = root.getElementsByTagName("testcase");
        require(cases.getLength() == expectedNames.size(), "testcase count mismatch");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element c = (Element) cases.item(i);
            require(c.getParentNode() == root, "nested testcase");
            require(c.getAttribute("classname").equals(suite), "wrong testcase class");
            require(c.getAttribute("status").isBlank(), "unexpected testcase status");
            requireFiniteSeconds(c.getAttribute("time"), "testcase time");
            String name = c.getAttribute("name");
            require(!name.isBlank() && names.add(name), "empty/duplicate testcase");
        }
        require(names.equals(expectedNames), "unexpected testcase identity");

        for (String forbidden : List.of("failure", "error", "skipped", "testsuite")) {
            require(root.getElementsByTagName(forbidden).getLength() == 0,
                    "unexpected " + forbidden);
        }
    }
}
