package dev.oreslang.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Fail-closed, dependency-free validation of actual Maven Surefire XML.
 * This is CI metadata validation, not source checkout or guest attestation.
 *
 * Run with Java 21 source-file mode:
 *   java tools/VerifyAssuranceSurefire.java target/surefire-reports
 */
final class VerifyAssuranceSurefire {
    private record Suite(String className, List<String> required) { }

    private static final List<Suite> SUITES = List.of(
        new Suite("dev.oreslang.CriticalActorGuestExecutionTest", List.of(
            "actorCriticalGuestReturnsExpectedValueAndReleasesBeforeStop",
            "earlyReturnInsideCriticalReleasesGuardBeforeActorStop",
            "canonicalAttoActorCriticalGuestMatchesLegacyGuestOutput",
            "canonicalAttoEarlyReturnGuestReleasesBeforeStop",
            "canonicalAttoGuestOutputTracksARealSourceMutation",
            "canonicalAttoGuestCriticalBodyMutationChangesOutput",
            "canonicalAttoGuestSourceMutationWorksInBothDirections",
            "unverifiedFreeFunctionCriticalSourceDoesNotExecute"
        )),
        new Suite("dev.oreslang.ActorBorrowCompilerAdmissionTest", List.of(
            "twoSharedBorrowsCanCoexistWithoutExclusiveWriter",
            "independentExclusiveBorrowIsAllowedWhenNoReadLoanExists",
            "exclusiveWriterIsRejectedWhenTwoReadBorrowsAreLive",
            "ownershipTransferFailsWhileSharedBorrowIsLive",
            "sourceOwnerIsUnusableAfterRtTakeMove",
            "evenOneSharedReaderBlocksExclusiveWriter",
            "exclusiveWriterBlocksNewSharedReader",
            "secondExclusiveWriterIsNeverAdmitted",
            "reassignmentOfOwnerDuringReadBorrowIsRejected",
            "lexicalReadBorrowReleaseAllowsLaterExclusiveWriter",
            "lexicalExclusiveBorrowReleaseAllowsLaterReader",
            "lexicalReadBorrowReleaseAllowsOwnershipTake",
            "doubleRtTakeOfSameOwnedBindingIsRejected"
        )),
        new Suite("dev.oreslang.CriticalSuspendsV1SyntaxTest", List.of(
            "concurrentRetypingCannotPublishPartialTaskEpochBeforeCriticalAdmission",
            "newerConcurrentOwnershipAttemptSupersedesBlockedOlderAttempt",
            "criticalTaskPublicationIsAtomicAgainstConcurrentTypingRevocation"
        )),
        new Suite("dev.oreslang.TaskCaptureAdmissionIdentityTest", List.of(
            "rawHostAstAndForgedEpochCannotPublishTaskCaptures",
            "untypedNoncriticalTaskCannotAcquireExecutableOwnership",
            "successfulTypeRecheckRevokesPreviouslyExecutableNoncriticalTask",
            "failedTypeRecheckRevokesPreviouslyExecutableNoncriticalTask",
            "publicStagedCapturePublisherCannotMintTaskTransferAuthority"
        )),
        new Suite("dev.oreslang.nodes.CriticalTicketCleanupTest", List.of(
            "immediateTicketIsReleasedAfterFailedSchedulerTransition",
            "queuedTicketIsRetractedWithoutReleasingAnotherOwner",
            "retiredAdmissionIsSafeToUnwind",
            "cancellationRacingWithPromotionCannotLeakActorOwnedTicket"
        )),
        new Suite("dev.oreslang.CriticalBodyEffectsTest", List.of()),
        new Suite("dev.oreslang.runtime.CriticalGateTest", List.of()),
        new Suite("dev.oreslang.runtime.ActorCriticalGateIdentityTest", List.of())
    );

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException(
            "Usage: java tools/VerifyAssuranceSurefire.java <surefire-directory>");
        Path reportRoot = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(reportRoot)) throw new IllegalArgumentException(
            "Surefire reports directory missing");
        int cases = 0;
        for (Suite expected : SUITES) {
            Path report = reportRoot.resolve("TEST-" + expected.className + ".xml");
            cases += verify(report, expected);
        }
        System.out.println("PASS " + SUITES.size() + " real XML suites, "
            + cases + " executed testcases, all named security gates present");
        System.out.println("SCOPED_JVM_SOURCE_AND_TEST_METADATA_ONLY; guest attestation NOT_VERIFIED");
    }

    private static void selfTest() throws Exception {
        Suite expected = new Suite("dev.oreslang.SecurityFixture", List.of("mustExecute"));
        Path report = Files.createTempFile("atto-xml-gate-", ".xml");
        String good = "<testsuite name=\"dev.oreslang.SecurityFixture\" tests=\"1\" " +
            "failures=\"0\" errors=\"0\" skipped=\"0\">" +
            "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
            "name=\"mustExecute\"/></testsuite>";
        try {
            Files.writeString(report, good);
            if (verify(report, expected) != 1) {
                throw new IllegalStateException("Valid Surefire fixture was not accepted");
            }
            expectRejected(report, expected, good.replace("failures=\"0\"", "failures=\"1\""));
            expectRejected(report, expected, good.replace("errors=\"0\"", "errors=\"1\""));
            expectRejected(report, expected, good.replace("skipped=\"0\"", "skipped=\"1\""));
            expectRejected(report, expected, good.replace("name=\"mustExecute\"", "name=\"wrong\""));
            expectRejected(report, expected, good.replace("tests=\"1\"", "tests=\"2\""));
            expectRejected(report, expected, "<!DOCTYPE testsuite [<!ENTITY data SYSTEM " +
                "\"file:///etc/passwd\">]>" + good);
            String testcase = "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
                "name=\"mustExecute\"/>";
            expectRejected(report, expected, good.replace(
                "name=\"dev.oreslang.SecurityFixture\"", "name=\"forged.Suite\""));
            expectRejected(report, expected, good.replace(
                "classname=\"dev.oreslang.SecurityFixture\"", "classname=\"forged.Suite\""));
            expectRejected(report, expected, good.replace("tests=\"1\"", "tests=\"0\""));
            expectRejected(report, expected, good.replace("tests=\"1\"", "tests=\"invalid\""));
            expectRejected(report, expected, good.replace("tests=\"1\"",
                "tests=\"999999999999999999999\""));
            expectRejected(report, expected, good.replace(
                "name=\"mustExecute\"", "name=\"\""));
            expectRejected(report, expected, good.replace(
                "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
                "name=\"mustExecute\"/>", ""));
            expectRejected(report, expected, good.replace("tests=\"1\"", "tests=\"2\"")
                .replace("</testsuite>", testcase + "</testsuite>"));
            expectRejected(report, expected, good.replace(testcase,
                "<system-out>" + testcase + "</system-out>"));
            expectRejected(report, expected, good.replace(testcase,
                "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
                "name=\"mustExecute\"><skipped/></testcase>"));
            expectRejected(report, expected, good.replace(testcase,
                "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
                "name=\"mustExecute\"><failure/></testcase>"));
            expectRejected(report, expected, good.replace(testcase,
                "<testcase classname=\"dev.oreslang.SecurityFixture\" " +
                "name=\"mustExecute\"><error/></testcase>"));
            expectRejected(report, expected, good.replace(testcase,
                testcase + "<unexpected/>"));
            expectRejected(report, expected, good.replace("failures=\"0\"", ""));

        } finally {
            Files.deleteIfExists(report);
        }
        System.out.println("PASS Surefire XML negative controls: malformed totals, " +
            "wrong/duplicate/missing/nested cases, forged suite names, hidden errors, and XXE");
    }

    private static void expectRejected(Path report, Suite expected, String xml) throws Exception {
        Files.writeString(report, xml);
        boolean rejected = false;
        try {
            verify(report, expected);
        } catch (Exception invalid) {
            rejected = true;
        }
        if (!rejected) throw new IllegalStateException(
            "Forged or invalid XML was accepted as evidence");
    }

    private static int verify(Path path, Suite expected) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) == 0
                || Files.size(path) > 8 * 1024 * 1024) {
            throw new IllegalStateException("Missing, empty, or oversized report: "
                + expected.className);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document parsed;
        try (InputStream stream = Files.newInputStream(path)) {
            parsed = factory.newDocumentBuilder().parse(stream);
        }
        Element suite = parsed.getDocumentElement();
        if (!"testsuite".equals(suite.getTagName())
                || !expected.className.equals(suite.getAttribute("name"))) {
            throw new IllegalStateException("Unexpected Surefire suite identity: "
                + expected.className);
        }
        int declared = positiveCount(suite, "tests");
        if (declared < 1 || positiveCount(suite, "failures") != 0
                || positiveCount(suite, "errors") != 0
                || positiveCount(suite, "skipped") != 0) {
            throw new IllegalStateException("Failed, skipped or empty test suite: "
                + expected.className);
        }
        // Descendant queries can count a fake <testcase> nested inside <system-out>
        // or other unrelated XML. Only direct test-suite testcase children qualify.
        NodeList nodes = suite.getChildNodes();
        java.util.ArrayList<Element> cases = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element child)) continue;
            String tag = child.getTagName();
            if ("testcase".equals(tag)) {
                cases.add(child);
            } else if (!Set.of("properties", "system-out", "system-err").contains(tag)) {
                throw new IllegalStateException("Unexpected Surefire suite child: " + tag);
            }
        }
        if (cases.size() != declared) throw new IllegalStateException(
            "Direct testcase count does not match Surefire declaration: " + expected.className);
        Set<String> observed = new HashSet<>();
        for (Element test : cases) {
            if (!expected.className.equals(test.getAttribute("classname"))) {
                throw new IllegalStateException("Testcase class mismatch in " + expected.className);
            }
            String name = test.getAttribute("name");
            if (name.isBlank() || !observed.add(name)) {
                throw new IllegalStateException("Unnamed or duplicate testcase in " + expected.className);
            }
            for (String forbidden : List.of("failure", "error", "skipped")) {
                if (test.getElementsByTagName(forbidden).getLength() != 0) {
                    throw new IllegalStateException("Failure/skip node in " + expected.className);
                }
            }
        }
        for (String required : expected.required) {
            if (!observed.contains(required)) {
                throw new IllegalStateException("Missing required test " + required
                    + " from " + expected.className);
            }
        }
        System.out.println("PASS Surefire " + expected.className + " tests=" + declared
            + " named_required=" + expected.required.size());
        return declared;
    }

    private static int positiveCount(Element element, String field) {
        try {
            String raw = element.getAttribute(field);
            if (!raw.matches("[0-9]+")) throw new NumberFormatException();
            return Integer.parseInt(raw);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid Surefire " + field + " counter");
        }
    }
}
