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
            "unverifiedFreeFunctionCriticalSourceDoesNotExecute"
        )),
        new Suite("dev.oreslang.ActorBorrowCompilerAdmissionTest", List.of(
            "twoSharedBorrowsCanCoexistWithoutExclusiveWriter",
            "independentExclusiveBorrowIsAllowedWhenNoReadLoanExists",
            "exclusiveWriterIsRejectedWhenTwoReadBorrowsAreLive",
            "ownershipTransferFailsWhileSharedBorrowIsLive",
            "sourceOwnerIsUnusableAfterRtTakeMove"
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
        NodeList cases = suite.getElementsByTagName("testcase");
        if (cases.getLength() != declared) throw new IllegalStateException(
            "Testcase count does not match Surefire declaration: " + expected.className);
        Set<String> observed = new HashSet<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element test = (Element) cases.item(i);
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
