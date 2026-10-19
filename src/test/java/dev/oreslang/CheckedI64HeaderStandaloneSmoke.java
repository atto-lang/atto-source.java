package dev.oreslang;

import dev.oreslang.parser.CheckedI64SourceHeader;
import java.util.Random;

/** Standalone fail-closed source directive scanner checks; no GraalVM dependency. */
public final class CheckedI64HeaderStandaloneSmoke {
    private static final String HEADER = "// oreslang:checked-i64-v1\n";
    private static final String MAIN = "pub fnc main(): int { return 43; }\n";
    private static int checks;
    private static final int EXPECTED_CHECKS = 24_622;

    private CheckedI64HeaderStandaloneSmoke() { }

    private static void checked(String source, boolean expect) {
        final boolean result = CheckedI64SourceHeader.isEnabled(source);
        if (result != expect) throw new AssertionError("directive negotiation incorrect: " + printable(source));
        checks++;
    }

    private static void rejects(String source) {
        try {
            CheckedI64SourceHeader.isEnabled(source);
        } catch (IllegalArgumentException valid) {
            if (!String.valueOf(valid.getMessage()).contains("checked-i64-v1")) {
                throw new AssertionError("missing checked profile category", valid);
            }
            checks++;
            return;
        }
        throw new AssertionError("silent fallback or acceptance: " + printable(source));
    }

    private static String printable(String source) {
        return source.length() > 240 ? source.substring(0, 240) + " ..." : source;
    }

    public static void main(String[] ignored) {
        checked(HEADER + MAIN, true);
        checked(MAIN, false);
        checked("// unrelated comment\n" + MAIN, false);
        checked(HEADER + "// unrelated comment\n" + MAIN, true);
        for (String candidate : new String[] {
            "// oreslang:checked-i64-v2\n", "// oreslang:checked-i64-v1\r\n",
            "// oreslang:checked-i64-v1 trailing\n", "// oreslang:checked-i64-v1",
            "//oreslang:checked-i64-v1\n", "//  oreslang:checked-i64-v1\n",
            "//\toreslang:checked-i64-v1\n", "//\roreslang:checked-i64-v1\n",
            " // oreslang:checked-i64-v1\n", "\n// oreslang:checked-i64-v1\n",
            "\uFEFF// oreslang:checked-i64-v1\n"
        }) {
            rejects(candidate + MAIN);
            rejects(HEADER + candidate + MAIN);
        }
        rejects(MAIN + HEADER);
        rejects("pub fnc main(): int { return 43; } // oreslang:checked-i64-v1\n");
        for (String hidden : new String[] {
            "/* // oreslang:checked-i64-v1 */\n",
            "/* outer /* nested // oreslang:checked-i64-v1 */ outer */\n",
            "'// oreslang:checked-i64-v1'\n", "\"// oreslang:checked-i64-v1\"\n",
            "'ignore \\' // oreslang:checked-i64-v1'\n",
            "\"ignore \\\" // oreslang:checked-i64-v1\"\n",
            "#'// oreslang:checked-i64-v1'\n", "#\"// oreslang:checked-i64-v1\"\n",
            "'abc\\n// oreslang:checked-i64-v1'\n"
        }) {
            checked(hidden + MAIN, false);
            checked(HEADER + hidden + MAIN, true);
        }
        Random random = new Random(20261019L);
        for (int i = 0; i < 4096; i++) {
            String spacing = switch (i % 4) {
                case 0 -> "  ";
                case 1 -> "\t";
                case 2 -> " \t ";
                default -> "\f\u000B";
            };
            String version = i % 2 == 0 ? "v1" : "v" + (2 + random.nextInt(17));
            String malformed = "//" + spacing + "oreslang:checked-i64-" + version + "\n";
            rejects(malformed + MAIN);
            rejects(HEADER + malformed + MAIN);
            String buried = "/* outer /* nested\n" + malformed + "*/ outer */\n";
            checked(buried + MAIN, false);
            checked(HEADER + buried + MAIN, true);
            String quoted = "\"before //" + spacing + "oreslang:checked-i64-" + version + " after\"\n";
            checked(quoted + MAIN, false);
            checked(HEADER + quoted + MAIN, true);
        }
        if (checks != EXPECTED_CHECKS) throw new AssertionError("coverage changed: " + checks);
        System.out.println("CHECKED_I64_HEADER_STANDALONE|checks=" + checks + "|passed=" + checks + "|failed=0|scope=source-negotiation");
    }
}
