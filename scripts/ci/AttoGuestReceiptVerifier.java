import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Source-owned, JDK-only, fail-closed Surefire guest evidence validator. */
public final class AttoGuestReceiptVerifier {
    private record Suite(String name, List<String> methods) { int count() { return methods.size(); } }
    private static final List<Suite> REQUIRED = List.of(
            new Suite("dev.oreslang.AttoCanonicalFixtureGuestTest", List.of("canonicalAttoFixtureParsesTypechecksAndResolvesExtensionlessImport", "canonicalAttoFixtureLinksAndExecutesGuestWithExpectedOutput", "absentCanonicalAttoHelperFailsClosedBeforeGuestExecution")),
            new Suite("dev.oreslang.AttoImportedModuleClassGuestTest", List.of("canonicalAttoPublicFileRootClassImportsAndRuns", "canonicalAttoPublicClassInsideImportedModuleResolvesAtExecution", "qualifiedConstructorCannotBindToUnrelatedLocalHomonym", "privateClassInImportedModuleIsNotConstructible", "unknownModuleAliasNeverResolvesLocalClass")));

    private static void require(boolean ok, String diagnostic) {
        if (!ok) throw new IllegalStateException(diagnostic);
    }

    private static int intAttribute(Element element, String name) {
        try { return Integer.parseInt(element.getAttribute(name)); }
        catch (NumberFormatException ex) {
            throw new IllegalStateException("missing/invalid JUnit attribute: " + name, ex);
        }
    }

    private static Document secureParse(Path report) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new IllegalStateException("JUnit XML external entity is forbidden");
        });
        return builder.parse(report.toFile());
    }

    static int verify(String expectedHead, Path reportDirectory) throws Exception {
        require(expectedHead.matches("[0-9a-f]{40}"), "invalid exact Git head");
        require(Files.isDirectory(reportDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(reportDirectory), "missing/symlinked JUnit report directory");
        int total = 0;
        for (Suite suite : REQUIRED) {
            Path path = reportDirectory.resolve("TEST-" + suite.name() + ".xml");
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(path) && Files.size(path) > 0 && Files.size(path) <= 1_048_576,
                    "missing, empty, or symlinked JUnit report for " + suite.name());
            Document document = secureParse(path);
            Element root = document.getDocumentElement();
            require(root != null && "testsuite".equals(root.getTagName()),
                    "incorrect JUnit root: " + suite.name());
            require(suite.name().equals(root.getAttribute("name")),
                    "JUnit suite identity mismatch: " + suite.name());
            require(intAttribute(root, "tests") == suite.count(), "incorrect JUnit test count: " + suite.name());
            for (String field : List.of("errors", "failures", "skipped")) {
                require(intAttribute(root, field) == 0,
                        "nonzero JUnit " + field + ": " + suite.name());
            }
            NodeList cases = root.getElementsByTagName("testcase");
            require(cases.getLength() == suite.count(),
                    "missing or unexpected testcase elements: " + suite.name());
            Set<String> names = new HashSet<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element testCase = (Element) cases.item(i);
                require(suite.name().equals(testCase.getAttribute("classname")),
                        "foreign testcase class: " + suite.name());
                String name = testCase.getAttribute("name");
                require(suite.methods().contains(name) && names.add(name),
                        "missing or duplicate testcase name: " + suite.name());
                require(testCase.getParentNode() == root, "nested testcase: " + suite.name());
                for (String failure : List.of("error", "failure", "skipped")) {
                    require(testCase.getElementsByTagName(failure).getLength() == 0,
                            "testcase failed/skipped: " + suite.name());
                }
            }
            require(names.containsAll(suite.methods()), "missing required testcase methods: " + suite.name());
            total += suite.count();
        }
        require(total == 8, "wrong aggregate test count");
        return total;
    }

    private static String report(Suite suite, int count, int errors, int failures, int skipped,
                                 boolean duplicate, boolean wrongClass, boolean evilDoctype) {
        StringBuilder xml = new StringBuilder();
        if (evilDoctype) xml.append("<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>");
        xml.append("<testsuite name=\"").append(suite.name()).append("\" tests=\"")
                .append(count).append("\" errors=\"").append(errors)
                .append("\" failures=\"").append(failures).append("\" skipped=\"")
                .append(skipped).append("\">");
        for (int i = 0; i < count; i++) {
            xml.append("<testcase classname=\"")
                    .append(wrongClass && i == 0 ? "unrelated.ForgedTest" : suite.name())
                    .append("\" name=\"").append(suite.methods().get(duplicate && i == 1 ? 0 : i))
                    .append("\"/>");
        }
        xml.append("</testsuite>");
        return xml.toString();
    }

    private static void writeReport(Path dir, Suite suite, int count, int errors, int failures,
                                    int skipped, boolean duplicate, boolean wrongClass,
                                    boolean evilDoctype) throws Exception {
        Files.writeString(dir.resolve("TEST-" + suite.name() + ".xml"),
                report(suite, count, errors, failures, skipped, duplicate, wrongClass, evilDoctype));
    }

    private static void shouldDeny(String name, String head, Path dir) throws Exception {
        boolean denied = false;
        try { verify(head, dir); }
        catch (Exception expected) { denied = true; }
        require(denied, "verifier accepted invalid evidence: " + name);
        System.out.println("PASS negative " + name);
    }

    private static void shouldDenyForReason(String name, String reason, String head, Path dir)
            throws Exception {
        try {
            verify(head, dir);
        } catch (IllegalStateException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(reason),
                    "wrong rejection reason for " + name + ": " + expected.getMessage());
            System.out.println("PASS negative " + name + " reason=" + reason);
            return;
        }
        throw new IllegalStateException("verifier accepted invalid evidence: " + name);
    }

    private static void selfTest() throws Exception {
        Path dir = Files.createTempDirectory("atto-junit-receipt-");
        String head = "0123456789abcdef0123456789abcdef01234567";
        Suite one = REQUIRED.get(0), two = REQUIRED.get(1);
        try {
            for (Suite suite : REQUIRED) {
                writeReport(dir, suite, suite.count(), 0, 0, 0, false, false, false);
            }
            require(verify(head, dir) == 8, "valid receipt rejected");
            System.out.println("PASS valid-eight-test-receipt");
            shouldDeny("invalid-head", "not-a-sha", dir);
            Files.delete(dir.resolve("TEST-" + two.name() + ".xml"));
            shouldDeny("missing-suite", head, dir);
            writeReport(dir, two, 5, 0, 0, 0, false, false, false);
            writeReport(dir, one, 2, 0, 0, 0, false, false, false);
            shouldDeny("short-suite", head, dir);
            writeReport(dir, one, 3, 1, 0, 0, false, false, false);
            shouldDeny("errors", head, dir);
            writeReport(dir, one, 3, 0, 1, 0, false, false, false);
            shouldDeny("failures", head, dir);
            writeReport(dir, one, 3, 0, 0, 1, false, false, false);
            shouldDeny("skipped", head, dir);
            writeReport(dir, one, 3, 0, 0, 0, true, false, false);
            shouldDeny("duplicate-case", head, dir);
            writeReport(dir, one, 3, 0, 0, 0, false, true, false);
            shouldDeny("foreign-class", head, dir);
            writeReport(dir, one, 3, 0, 0, 0, false, false, true);
            shouldDeny("external-doctype", head, dir);
            writeReport(dir, one, 3, 0, 0, 0, false, false, false);
            Path adversarialReport = dir.resolve("TEST-" + one.name() + ".xml");
            String validXml = Files.readString(adversarialReport);
            Files.writeString(adversarialReport, validXml.replace(
                    one.methods().get(0), "forgedMethodWithMatchingCount"));
            shouldDenyForReason("forged-method-name", "missing or duplicate testcase name", head, dir);
            Files.writeString(adversarialReport, validXml.replace(
                    "</testsuite>", "<testcase classname=\"" + one.name()
                            + "\" name=\"extra\"/></testsuite>"));
            // These negative fixtures must parse successfully; rejection must be semantic.
            secureParse(adversarialReport);
            shouldDenyForReason("extra-testcase", "missing or unexpected testcase elements", head, dir);
            Files.writeString(adversarialReport, validXml.replaceFirst(
                    "<testcase", "<wrapper><testcase").replaceFirst(
                    "/>", "/></wrapper>"));
            secureParse(adversarialReport);
            shouldDenyForReason("nested-testcase", "nested testcase", head, dir);
            Files.writeString(adversarialReport, validXml + " ".repeat(1_048_576));
            shouldDeny("oversized-report", head, dir);
            Files.writeString(adversarialReport, validXml);
            Path original = dir.resolve("TEST-" + one.name() + ".xml");
            Path symlink = dir.resolve("other.xml");
            try {
                Files.move(original, symlink);
                Files.createSymbolicLink(original, symlink.getFileName());
                shouldDeny("symlinked-report", head, dir);
            } catch (UnsupportedOperationException | IOException unsupported) {
                System.out.println("SKIP symlinked-report (platform unavailable)");
            }
            System.out.println("ATTO_SOURCE_FREE_RECEIPT_SELF_TEST_PASS");
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
        require(args.length == 2, "expected: <40-hex-head> <surefire-report-directory>");
        int count = verify(args[0], Path.of(args[1]));
        System.out.println("ATTO_JUNIT_GUEST_RECEIPT_PASS head=" + args[0] + " cases=" + count);
    }
}
