import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Offline, synthetic adversarial checks of the Surefire XML verifier.
 * These deliberately fake JUnit XML reports and NEVER certify compiled guests.
 */
public final class CheckCanonicalXorReportsSelfTest {
    private static final String XOR = "AttoCanonicalIntegerXorParityTest";
    private static final String BOOL = "BooleanIntrinsicsTest";
    private static final List<String> XOR_CASES = List.of(
            "strictIntegerXorCompilesAndRunsInActualTruffleGuest",
            "singleCaretReturnAndContiguousIntegerXorWorkTogether",
            "canonicalBooleanXorAndMixedScalarKindsFailDuringAdmission",
            "rejectedSingleOrSpacedCaretNeverCreatesCheckedAst",
            "canonicalIntegerXorRhsFailureExecutesEffectsOnlyOnce",
            "legacyParserAndTypecheckerRetainEagerBooleanXor",
            "booleanXorLegacyGuestStillWorks",
            "strictSignedI64XorPrecedenceAndLeftAssociativeChain",
            "strictXorSuccessEvaluatesEachOperandExactlyOnceFromLeftToRight",
            "strictBooleanShortCircuitStillSkipsInvalidRightOperands",
            "strictTripleCaretNeverBecomesXorOrReturn",
            "strictBooleanInequalityReplacesLegacyBooleanXor");
    private static final List<String> BOOL_CASES = List.of(
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
            "runtimeUsesLogicalIdentitiesParityAndScalarShortCircuiting");

    private CheckCanonicalXorReportsSelfTest() { }

    private static String report(String suite, List<String> names) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\"?>\n");
        out.append("<testsuite name=\"").append("dev.oreslang.").append(suite)
                .append("\" tests=\"").append(names.size())
                .append("\" errors=\"0\" failures=\"0\" skipped=\"0\">\n");
        for (String name : names) out.append("  <testcase classname=\"dev.oreslang.")
                .append(suite).append("\" name=\"").append(name).append("\"/>\n");
        return out.append("</testsuite>\n").toString();
    }

    private static Path reportPath(Path root, String suite) {
        return root.resolve("target/surefire-reports/TEST-dev.oreslang." + suite + ".xml");
    }

    private static final class Corpus {
        final Path root;
        Corpus(Path root) throws IOException {
            this.root = root;
            Files.createDirectories(root.resolve("target/surefire-reports"));
            Files.writeString(reportPath(root, XOR), report(XOR, XOR_CASES), StandardCharsets.UTF_8);
            Files.writeString(reportPath(root, BOOL), report(BOOL, BOOL_CASES), StandardCharsets.UTF_8);
        }
        void rewrite(String suite, String from, String to) throws IOException {
            Path path = reportPath(root, suite);
            String old = Files.readString(path, StandardCharsets.UTF_8);
            if (!old.contains(from)) throw new AssertionError("missing source string " + from);
            Files.writeString(path, old.replace(from, to), StandardCharsets.UTF_8);
        }
        void write(String suite, String xml) throws IOException {
            Files.writeString(reportPath(root, suite), xml, StandardCharsets.UTF_8);
        }
    }

    private static final class Result {
        final int exit;
        final String text;
        Result(int exit, String text) { this.exit=exit; this.text=text; }
    }

    private static Result invoke(Path root) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath();
        Path classPath = Path.of(System.getProperty("java.class.path")).toAbsolutePath();
        Process child = new ProcessBuilder(java.toString(), "-cp", classPath.toString(),
                "CheckCanonicalXorReports").directory(root.toFile()).redirectErrorStream(true).start();
        if (!child.waitFor(15, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("verifier hung on synthetic XML");
        }
        String text = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(child.exitValue(), text);
    }

    private static void run(String name, boolean shouldPass, Consumer<Corpus> mutation) throws Exception {
        Path root = Files.createTempDirectory("atto-xor-report-test-");
        try {
            Corpus c = new Corpus(root);
            mutation.accept(c);
            Result result = invoke(root);
            boolean actual = result.exit == 0;
            if (actual != shouldPass) throw new AssertionError(name + " expected success=" + shouldPass
                    + ", exit=" + result.exit + "\n" + result.text);
            if (actual && !result.text.contains("required_java_xor_junit=PASS"))
                throw new AssertionError("baseline did not emit an expected synthetic report receipt");
            if (!actual && result.text.contains("required_java_xor_junit=PASS"))
                throw new AssertionError("invalid synthetic XML emitted aggregate PASS");
        } finally {
            try (Stream<Path> all = Files.walk(root)) {
                for (Path path : all.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("SELFTEST " + name + "=" + (shouldPass ? "POSITIVE_SYNTHETIC_ONLY" : "REJECTED_SYNTHETIC_ONLY"));
    }

    private static Consumer<Corpus> mutation(CheckedConsumer<Corpus> f) {
        return c -> {try {f.accept(c);} catch (Exception ex) {throw new RuntimeException(ex);} };
    }
    @FunctionalInterface interface CheckedConsumer<T> {void accept(T value) throws Exception;}

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("no arguments permitted");
        run("complete-synthetic-report",true,c -> {});
        run("missing-xor-report",false,mutation(c -> Files.delete(reportPath(c.root, XOR))));
        run("missing-boolean-report",false,mutation(c -> Files.delete(reportPath(c.root, BOOL))));
        run("missing-required-case",false,mutation(c -> c.write(XOR, report(XOR, XOR_CASES.subList(1, XOR_CASES.size())))));
        run("wrong-classname",false,mutation(c -> c.rewrite(XOR,"classname=\"dev.oreslang."+XOR+"\"", "classname=\"dev.oreslang.ForgedSuite\"")));
        run("duplicate-case",false,mutation(c -> c.rewrite(XOR, "name=\""+XOR_CASES.get(1)+"\"", "name=\""+XOR_CASES.get(0)+"\"")));
        run("skipped-count",false,mutation(c -> c.rewrite(XOR,"skipped=\"0\"","skipped=\"1\"")));
        run("failure-count",false,mutation(c -> c.rewrite(XOR,"failures=\"0\"","failures=\"1\"")));
        run("hidden-skipped-element",false,mutation(c -> c.rewrite(XOR,"/>","><skipped/></testcase>")));
        run("hidden-failure-element",false,mutation(c -> c.rewrite(XOR,"/>","><failure/></testcase>")));
        run("bad-suite-name",false,mutation(c -> c.rewrite(XOR,"name=\"dev.oreslang."+XOR+"\" tests=", "name=\"ForgedSuite\" tests=")));
        run("nonintegral-tests-count",false,mutation(c -> c.rewrite(XOR,"tests=\"12\"","tests=\"12.0\"")));
        run("doctype-entity",false,mutation(c -> c.write(XOR,"<?xml version=\"1.0\"?><!DOCTYPE testsuite [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"+report(XOR,XOR_CASES).replace("<?xml version=\"1.0\"?>", ""))));
        run("nested-testsuites-root",false,mutation(c -> c.write(XOR, "<testsuites>"+report(XOR,XOR_CASES).replace("<?xml version=\"1.0\"?>", "")+"</testsuites>")));
        run("symlink-report",false,mutation(c -> {
            Path report = reportPath(c.root, XOR);
            Path target = c.root.resolve("forged.xml");
            Files.move(report,target);
            Files.createSymbolicLink(report,target);
        }));
        run("symlink-report-directory",false,mutation(c -> {
            Path dir = c.root.resolve("target/surefire-reports");
            Path relocated = c.root.resolve("forged-reports");
            Files.move(dir, relocated);
            Files.createSymbolicLink(dir, relocated);
        }));
        run("symlink-target-directory",false,mutation(c -> {
            Path dir = c.root.resolve("target");
            Path relocated = c.root.resolve("forged-target");
            Files.move(dir, relocated);
            Files.createSymbolicLink(dir, relocated);
        }));
        System.out.println("SELFTEST_VERIFIER_POLICY_ONLY cases=17 passed=17 guest_executed=0");
    }
}
