import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Source-free CI guard: reject absent, all-skipped, duplicated, or forged summary-only JUnit XML. */
class SurefireReceiptGuard {
    private static final long MAX_XML_BYTES = 8L * 1024 * 1024;

    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalArgumentException(why);
    }

    private static int count(Element suite, String name) {
        String raw = suite.getAttribute(name);
        require(raw.matches("(?:0|[1-9][0-9]{0,8})"), "invalid " + name + ": " + raw);
        return Integer.parseInt(raw);
    }

    private static Document parse(Path report) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var stream = Files.newInputStream(report)) {
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        var result = new StringBuilder();
        for (byte b : digest) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        require(args.length >= 3, "usage: SurefireReceiptGuard REPORT_DIRECTORY RECEIPT_FILE REQUIRED_SUITE...");
        Path dir = Path.of(args[0]);
        Path receipt = Path.of(args[1]);
        require(Files.isDirectory(dir) && !Files.isSymbolicLink(dir), "missing or symlinked report directory");
        Set<String> required = new LinkedHashSet<>(Arrays.asList(args).subList(2, args.length));
        require(required.size() == args.length - 2, "duplicate requested suite");
        for (String name : required) {
            require(name.matches("[A-Za-z_$][A-Za-z0-9_$]*"), "invalid suite name: " + name);
        }
        Map<String, String> receipts = new LinkedHashMap<>();
        int totalExecuted = 0;
        try (DirectoryStream<Path> reports = Files.newDirectoryStream(dir, "TEST-*.xml")) {
            for (Path report : reports) {
                require(!Files.isSymbolicLink(report) && Files.isRegularFile(report), "unsafe XML report: " + report);
                require(Files.size(report) > 0 && Files.size(report) <= MAX_XML_BYTES, "invalid report size: " + report);
                Document doc = parse(report);
                Element suite = doc.getDocumentElement();
                require("testsuite".equals(suite.getTagName()), "non-testsuite XML: " + report);
                String actualName = suite.getAttribute("name");
                String selected = null;
                for (String name : required) {
                    if (actualName.equals(name) || actualName.endsWith("." + name)) {
                        require(selected == null, "ambiguous suite: " + actualName);
                        selected = name;
                    }
                }
                if (selected == null) continue;
                require(!receipts.containsKey(selected), "duplicate suite report: " + selected);
                int tests = count(suite, "tests");
                int skips = count(suite, "skipped");
                int errors = count(suite, "errors");
                int failures = count(suite, "failures");
                require(tests > 0 && skips <= tests, "empty/all-skipped suite: " + selected);
                require(errors == 0 && failures == 0, "failing suite: " + selected);
                int testcaseCount = suite.getElementsByTagName("testcase").getLength();
                int skippedCount = suite.getElementsByTagName("skipped").getLength();
                require(testcaseCount == tests && skippedCount == skips,
                        "JUnit summary/testcase mismatch: " + selected);
                require(tests - skips > 0, "all-skipped suite: " + selected);
                totalExecuted = Math.addExact(totalExecuted, tests - skips);
                receipts.put(selected, selected + "\ttests=" + tests + "\tskipped=" + skips
                        + "\texecuted=" + (tests - skips) + "\tsha256=" + sha256(report)
                        + "\tfile=" + report.getFileName());
            }
        }
        require(receipts.size() == required.size(), "missing selected suite: " + required + " seen=" + receipts.keySet());
        var output = new StringBuilder("format=surefire-executed-v1\texecuted=" + totalExecuted + "\n");
        for (String name : required) output.append(receipts.get(name)).append('\n');
        Path parent = receipt.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(receipt, output.toString(), StandardCharsets.UTF_8);
        System.out.print(output);
    }
}
